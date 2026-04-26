package io.appfirewall.core.buffer;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EventBufferTest {

    @Test
    void emitAndDrain() {
        EventBuffer b = new EventBuffer(8);
        b.emit(Map.of("event", "http", "status", 200));
        b.emit(Map.of("event", "http", "status", 404));
        assertEquals(2, b.size());

        List<Map<String, Object>> out = new ArrayList<>();
        b.drainTo(out, 10);
        assertEquals(2, out.size());
        assertEquals(0, b.size());
    }

    @Test
    void emitNullIsNoop() {
        EventBuffer b = new EventBuffer(2);
        b.emit(null);
        assertEquals(0, b.size());
    }

    @Test
    void overflowDropsOldest() {
        EventBuffer b = new EventBuffer(3);
        b.emit(Map.of("seq", 1));
        b.emit(Map.of("seq", 2));
        b.emit(Map.of("seq", 3));
        // Buffer full; this should drop seq=1 (oldest) and keep 2,3,4.
        b.emit(Map.of("seq", 4));

        List<Map<String, Object>> out = new ArrayList<>();
        b.drainTo(out, 10);
        assertEquals(3, out.size());
        assertEquals(2, out.get(0).get("seq"));
        assertEquals(3, out.get(1).get("seq"));
        assertEquals(4, out.get(2).get("seq"));
        assertTrue(b.droppedOverflowCount() >= 1L);
    }
}
