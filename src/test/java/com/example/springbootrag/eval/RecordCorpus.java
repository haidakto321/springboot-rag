package com.example.springbootrag.eval;

import com.example.springbootrag.web.dto.RecordRequest;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** A deterministic synthetic record corpus: same bytes on every machine, so CI can run the eval. */
public final class RecordCorpus {

    private static final ObjectMapper M = new ObjectMapper();

    static final List<String> CUSTOMERS =
            List.of("ACME Corp", "GLOBEX Ltd", "Initech", "Umbrella SA", "Soylent BV");
    static final List<String> STATUSES = List.of("open", "overdue", "paid");
    static final List<String> CARRIERS = List.of("Speedy Freight", "NordCargo", "AirLift");
    static final List<String> PARTIES = List.of("ACME Corp", "Initech", "Umbrella SA");

    private RecordCorpus() {}

    public static List<RecordRequest> generate(long seed) {
        Random rnd = new Random(seed);
        List<RecordRequest> out = new ArrayList<>(210);
        for (int i = 0; i < 120; i++) out.add(invoice(i, rnd));
        for (int i = 0; i < 60; i++) out.add(deliveryNote(i, rnd));
        for (int i = 0; i < 30; i++) out.add(contract(i, rnd));
        return out;
    }

    private static RecordRequest invoice(int i, Random rnd) {
        String customer = CUSTOMERS.get(rnd.nextInt(CUSTOMERS.size()));
        String status = STATUSES.get(rnd.nextInt(STATUSES.size()));
        int month = 1 + rnd.nextInt(12);
        double total = 100 + rnd.nextInt(9900) + 0.5;
        double conf = 0.4 + rnd.nextInt(60) / 100.0;
        String json = """
                {"invoiceNumber":"INV-%04d",
                 "issueDate":"2026-%02d-15",
                 "status":"%s",
                 "total":%s,
                 "customer":{"value":"%s","confidence":%s,"grounding":{"page":1,"bbox":[10,20,30,40]}},
                 "notes":"%s",
                 "lineItems":[{"sku":"SKU-%03d","description":"consulting hours","amount":%s}]}
                """.formatted(i, month, status, total, customer, conf,
                status.equals("overdue") ? "payment is late, reminder sent" : "payment received on time",
                i % 50, total);
        return request("INV-%04d".formatted(i), "invoice", json);
    }

    private static RecordRequest deliveryNote(int i, Random rnd) {
        String carrier = CARRIERS.get(rnd.nextInt(CARRIERS.size()));
        int month = 1 + rnd.nextInt(12);
        String json = """
                {"shipmentId":"DN-%04d",
                 "deliveredOn":"2026-%02d-08",
                 "carrier":{"value":"%s","confidence":0.9},
                 "remarks":"goods delivered in full",
                 "packages":[{"trackingId":"TRK-%04d","weightKg":%s,"contents":"spare parts"}]}
                """.formatted(i, month, carrier, i, 1 + rnd.nextInt(40));
        return request("DN-%04d".formatted(i), "delivery-note", json);
    }

    private static RecordRequest contract(int i, Random rnd) {
        String party = PARTIES.get(rnd.nextInt(PARTIES.size()));
        int months = 12 * (1 + rnd.nextInt(3));
        String json = """
                {"contractId":"CT-%04d",
                 "effectiveDate":"2026-%02d-01",
                 "termMonths":%d,
                 "value":%d,
                 "party":{"value":"%s","confidence":0.88},
                 "summary":"annual support and maintenance agreement"}
                """.formatted(i, 1 + rnd.nextInt(12), months, 10000 + i * 500, party);
        return request("CT-%04d".formatted(i), "contract", json);
    }

    private static RecordRequest request(String docId, String docType, String json) {
        try {
            return new RecordRequest(docId, docType, M.readTree(json), null, List.of("public"), null);
        } catch (Exception e) {
            throw new IllegalStateException("bad corpus record " + docId, e);
        }
    }
}
