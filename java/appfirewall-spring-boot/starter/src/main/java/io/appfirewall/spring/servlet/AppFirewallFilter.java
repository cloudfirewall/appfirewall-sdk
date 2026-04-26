package io.appfirewall.spring.servlet;

import io.appfirewall.core.Client;
import io.appfirewall.core.classifier.Classification;
import io.appfirewall.core.context.RequestContext;
import io.appfirewall.core.ip.CfMetadata;
import io.appfirewall.spring.context.RequestContextHolder;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Servlet-stack filter mirroring the request lifecycle from
 * {@code python/appfirewall-fastapi/src/appfirewall_fastapi/_middleware.py}.
 *
 * <p>Order: registered with {@code Ordered.HIGHEST_PRECEDENCE + 100} so we
 * see scanner probes even when Spring Security blocks them downstream.
 *
 * <p>Fail-open: every {@link Throwable} from our own code is caught and
 * logged at FINE; the filter chain continues. Only the customer's own
 * exceptions propagate.
 */
public final class AppFirewallFilter extends OncePerRequestFilter {

    private static final Logger LOG = Logger.getLogger("appfirewall");

    private final Client client;

    public AppFirewallFilter(Client client) {
        this.client = client;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest req,
            HttpServletResponse res,
            FilterChain chain
    ) throws ServletException, IOException {

        RequestContext ctx;
        AutoCloseable token;
        try {
            ctx = buildContext(req);
            token = RequestContextHolder.bind(ctx);
        } catch (Throwable t) {
            LOG.log(Level.FINE, "appfirewall: context build failed", t);
            chain.doFilter(req, res);
            return;
        }

        Throwable propagating = null;
        try {
            chain.doFilter(req, res);
        } catch (RuntimeException | IOException | ServletException e) {
            propagating = e;
            // Customer handler raised. Record the status as 500 if no real
            // status was sent yet, then re-throw so the container handles it.
            if (ctx.status() == 0) ctx.setStatus(500);
            throw e;
        } finally {
            try {
                if (propagating == null) {
                    ctx.setStatus(res.getStatus());
                }
                postResponse(ctx);
            } catch (Throwable t) {
                LOG.log(Level.FINE, "appfirewall: post-response hook failed", t);
            }
            try {
                token.close();
            } catch (Throwable t) {
                LOG.log(Level.FINE, "appfirewall: context unbind failed", t);
            }
        }
    }

    private RequestContext buildContext(HttpServletRequest req) {
        Map<String, String> headers = new HashMap<>();
        Enumeration<String> names = req.getHeaderNames();
        while (names != null && names.hasMoreElements()) {
            String name = names.nextElement();
            // Take the first occurrence; mirror FastAPI's setdefault behaviour.
            headers.putIfAbsent(name.toLowerCase(Locale.ROOT), req.getHeader(name));
        }

        String peer = req.getRemoteAddr();
        String ip = client.ipResolver().resolveClientIp(headers, peer);
        CfMetadata cf = client.extractCfMetadata(headers, peer);
        Map<String, String> cfMap = cf.isEmpty() ? null : flatten(cf);

        return new RequestContext(
                req.getMethod(),
                req.getRequestURI() == null ? "/" : req.getRequestURI(),
                ip,
                headers.get("user-agent"),
                cfMap,
                System.nanoTime()
        );
    }

    private static Map<String, String> flatten(CfMetadata cf) {
        Map<String, String> m = new LinkedHashMap<>();
        if (cf.country() != null) m.put("country", cf.country());
        if (cf.ray() != null) m.put("ray", cf.ray());
        if (cf.asn() != null) m.put("asn", cf.asn());
        return m;
    }

    private void postResponse(RequestContext ctx) {
        Classification classification = null;
        if (ctx.status() == 404 && client.config().classify404()) {
            classification = client.classifyPath(ctx.path());
            // Feed the rate limiter; v0.1 enforcement default is off, so
            // we record either way.
            client.rateLimited(ctx.ip(), classification.wire());
        }
        client.recordHttpEvent(ctx, classification);
    }
}
