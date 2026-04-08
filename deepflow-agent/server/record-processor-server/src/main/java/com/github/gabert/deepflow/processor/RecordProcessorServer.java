package com.github.gabert.deepflow.processor;

public class RecordProcessorServer {
    private final ProcessorConfig config;
    private KafkaRecordConsumer consumer;

    public RecordProcessorServer(ProcessorConfig config) {
        this.config = config;
    }

    public void start() {
        RecordSink sink = new LoggingSink();
        consumer = new KafkaRecordConsumer(config, sink);

        System.out.println("RecordProcessorServer started — consuming from topic '"
                + config.getKafkaTopic() + "'");

        consumer.pollLoop();
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
