package bench;

import java.math.BigDecimal;

/**
 * Enterprise-shaped workload: three nested traced calls per process()
 * invocation, passing the Invoice aggregate through a service layer the
 * way business code does.
 */
public class InvoiceService {

    public BigDecimal process(Invoice invoice, String requestedBy) {
        boolean ok = validate(invoice, requestedBy);
        if (!ok) return BigDecimal.ZERO;
        return settle(invoice, invoice.getTotalNet(), "EUR");
    }

    public boolean validate(Invoice invoice, String requestedBy) {
        return invoice != null
                && invoice.getStatus() != Invoice.Status.CANCELLED
                && !invoice.getItems().isEmpty()
                && requestedBy != null;
    }

    public BigDecimal settle(Invoice invoice, BigDecimal amount, String currency) {
        if ("EUR".equals(currency)) return amount;
        return amount.multiply(BigDecimal.valueOf(1.1));
    }
}
