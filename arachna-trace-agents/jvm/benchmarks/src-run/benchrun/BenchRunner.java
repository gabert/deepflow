package benchrun;

import bench.BenchTarget;
import bench.Invoice;
import bench.InvoiceService;
import bench.Order;
import bench.StrippedTarget;

import com.sun.net.httpserver.HttpServer;

import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Measures app-thread cost of traced calls. Lives outside the instrumented
 * package (matchers_include=bench\..*) so the harness itself is not traced.
 *
 * Usage: BenchRunner <normal|stripped|business> <warmupIters> <itersPerRound> <rounds>
 * One iteration = process() -> validate() + settle/price() = 3 traced calls.
 *
 * Always starts a dummy collector on 18099 that swallows POSTs with 200 —
 * harmless in file mode, required for destination=http runs.
 */
public class BenchRunner {

    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0] : "normal";
        int warmup = args.length > 1 ? Integer.parseInt(args[1]) : 10_000;
        int iters  = args.length > 2 ? Integer.parseInt(args[2]) : 3_000;
        int rounds = args.length > 3 ? Integer.parseInt(args[3]) : 5;

        startDummyCollector();

        long[] nsPerIter = new long[rounds];
        double sink;

        switch (mode) {
            case "business" -> {
                InvoiceService service = new InvoiceService();
                Invoice invoice = buildInvoice();
                sink = runBusiness(service, invoice, warmup);
                for (int r = 0; r < rounds; r++) {
                    System.gc();
                    long t0 = System.nanoTime();
                    sink += runBusiness(service, invoice, iters);
                    long t1 = System.nanoTime();
                    nsPerIter[r] = (t1 - t0) / iters;
                    System.out.println("ROUND " + r + ": " + nsPerIter[r] + " ns/iter (3 traced calls)");
                }
            }
            case "stripped" -> {
                StrippedTarget target = new StrippedTarget();
                Order order = new Order(42L, "alice", List.of("book", "pencil", "lamp"), 129.90);
                sink = runStripped(target, order, warmup);
                for (int r = 0; r < rounds; r++) {
                    System.gc();
                    long t0 = System.nanoTime();
                    sink += runStripped(target, order, iters);
                    long t1 = System.nanoTime();
                    nsPerIter[r] = (t1 - t0) / iters;
                    System.out.println("ROUND " + r + ": " + nsPerIter[r] + " ns/iter (3 traced calls)");
                }
            }
            default -> {
                BenchTarget target = new BenchTarget();
                Order order = new Order(42L, "alice", List.of("book", "pencil", "lamp"), 129.90);
                sink = runNormal(target, order, warmup);
                for (int r = 0; r < rounds; r++) {
                    System.gc();
                    long t0 = System.nanoTime();
                    sink += runNormal(target, order, iters);
                    long t1 = System.nanoTime();
                    nsPerIter[r] = (t1 - t0) / iters;
                    System.out.println("ROUND " + r + ": " + nsPerIter[r] + " ns/iter (3 traced calls)");
                }
            }
        }

        long[] sorted = nsPerIter.clone();
        Arrays.sort(sorted);
        System.out.println("MEDIAN: " + sorted[rounds / 2] + " ns/iter (" + mode + ", sink=" + sink + ")");
        // The dummy collector's server thread is non-daemon; exit explicitly
        // (this also runs the agent's shutdown hook, draining its buffer).
        System.exit(0);
    }

    private static double runNormal(BenchTarget target, Order order, int n) {
        double acc = 0;
        for (int i = 0; i < n; i++) {
            acc += target.process(order, (i % 7) + 1);
        }
        return acc;
    }

    private static double runStripped(StrippedTarget target, Order order, int n) {
        double acc = 0;
        for (int i = 0; i < n; i++) {
            acc += target.process(order, (i % 7) + 1);
        }
        return acc;
    }

    private static double runBusiness(InvoiceService service, Invoice invoice, int n) {
        double acc = 0;
        for (int i = 0; i < n; i++) {
            acc += service.process(invoice, "user-" + (i % 3)).doubleValue();
        }
        return acc;
    }

    private static Invoice buildInvoice() {
        Invoice.Address billing = new Invoice.Address("Hlavna 42", "Bratislava", "81101", "SK");
        Invoice.Address shipping = new Invoice.Address("Obchodna 7", "Bratislava", "81106", "SK");
        Invoice.Customer customer = new Invoice.Customer(
                77031L, "Acme Manufacturing s.r.o.", "ap@acme-manufacturing.example", billing, shipping);

        List<Invoice.LineItem> items = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (int i = 0; i < 12; i++) {
            Map<String, String> attrs = new LinkedHashMap<>();
            attrs.put("warehouse", i % 2 == 0 ? "BA-01" : "KE-02");
            attrs.put("taxCode", "VAT20");
            Invoice.LineItem item = new Invoice.LineItem(
                    "SKU-" + (1000 + i),
                    "Industrial bearing unit type " + (char) ('A' + i),
                    (i % 5) + 1,
                    new BigDecimal("129.9" + i),
                    i % 3 == 0 ? 2.5 : 0.0,
                    attrs);
            items.add(item);
            total = total.add(item.total());
        }

        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("source", "e-shop");
        metadata.put("paymentTerms", "NET30");
        metadata.put("costCenter", "CC-4711");

        return new Invoice(900142L, "2026-INV-000901", Invoice.Status.ISSUED,
                customer, items, metadata, total);
    }

    private static void startDummyCollector() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(18099), 0);
            server.createContext("/records", exchange -> {
                byte[] body = exchange.getRequestBody().readAllBytes();
                byte[] response = "ok".getBytes();
                exchange.sendResponseHeaders(200, response.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(response);
                }
                // body intentionally discarded — this sink only measures the
                // agent side, not collector processing
                if (body.length < 0) System.out.print("");
            });
            server.start();
        } catch (Exception e) {
            System.err.println("dummy collector not started: " + e.getMessage());
        }
    }
}
