package com.platinumcoin.pix.settlement.domain.usecase;

import com.platinumcoin.pix.settlement.domain.port.AuditTrail;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** In-memory {@link AuditTrail}: records every object appended, and can be made to fail on demand. */
class FakeAuditTrail implements AuditTrail {

    record Written(String objectKey, List<String> jsonLines, Instant writtenAt) {
    }

    private final List<Written> written = new ArrayList<>();
    private boolean failing;

    @Override
    public String append(List<String> jsonLines, Instant writtenAt) {
        if (failing) {
            throw new IllegalStateException("the object store is unreachable");
        }
        String key = "2026/08/21/14/settlement-service-" + written.size() + ".jsonl";
        written.add(new Written(key, List.copyOf(jsonLines), writtenAt));
        return key;
    }

    List<Written> written() {
        return written;
    }

    void fail() {
        failing = true;
    }

    void recover() {
        failing = false;
    }
}
