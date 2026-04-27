package com.github.gabert.deepflow.processor;

public class RecordProcessorServer {
    private final ProcessorConfig config;
    private KafkaRecordConsumer consumer;

    public RecordProcessorServer(ProcessorConfig config) {
        this.config = config;
    }

    public void start() {
        RecordSink sink = createSink(config);
        consumer = new KafkaRecordConsumer(config, sink);

        System.out.println("RecordProcessorServer started — consuming from topic '"
                + config.getKafkaTopic() + "', sink=" + config.getSinkType());

        consumer.pollLoop();
    }

    private static RecordSink createSink(ProcessorConfig config) {
        return switch (config.getSinkType()) {
            case "logging" -> new LoggingSink();
            case "clickhouse" -> new ClickHouseSink(config);
            default -> throw new IllegalArgumentException(
                    "Unknown sink_type: " + config.getSinkType()
                            + " (expected: clickhouse | logging)");
        };
    }

    public void stop() {
        if (consumer != null) {
            consumer.shutdown();
            consumer.close();
        }
        System.out.println("RecordProcessorServer stopped.");
    }

    public static void main(String[] args) throws Exception {
        ProcessorConfig config = ProcessorConfig.load(args);
        RecordProcessorServer server = new RecordProcessorServer(config);

        Runtime.getRuntime().addShutdownHook(new Thread(server::stop));

        server.start();
    }
}
