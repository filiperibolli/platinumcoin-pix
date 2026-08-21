package com.platinumcoin.pix.ledger.domain.usecase;

import com.platinumcoin.pix.ledger.domain.model.ArchivedEntry;
import com.platinumcoin.pix.ledger.domain.port.StatementArchive;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

/** In-memory cold archive: records every monthly object written, in order. */
class FakeStatementArchive implements StatementArchive {

    record Written(String accountId, YearMonth month, List<ArchivedEntry> entries) {
    }

    private final List<Written> written = new ArrayList<>();

    @Override
    public String write(String accountId, YearMonth month, List<ArchivedEntry> entries) {
        written.add(new Written(accountId, month, List.copyOf(entries)));
        return "account=" + accountId + "/" + month + ".jsonl";
    }

    List<Written> written() {
        return written;
    }
}
