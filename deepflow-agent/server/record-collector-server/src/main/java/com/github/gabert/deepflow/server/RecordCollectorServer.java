package com.github.gabert.deepflow.server;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;

public class RecordCollectorServer {
    private final ServerConfig config;
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;

    public RecordCollectorServer(ServerConfig config) {
        this.config = config;
    }

    public void start() throws InterruptedException {
        bossGroup = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup();

        ServerBootstrap bootstrap = new ServerBootstrap();
        bootstrap.group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline().addLast(
                                new HttpServerCodec(),
                                new HttpObjectAggregator(config.getMaxContentLength()),
                                new RecordHandler());
                    }
                });

        serverChannel = bootstrap.bind(config.getServerPort()).sync().channel();
        System.out.println("RecordCollectorServer started on port " + config.getServerPort());
    }

    public void stop() {
        if (serverChannel != null) {
            serverChannel.close();
        }
        if (bossGroup != null) {
            bossGroup.shutdownGracefully();
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully();
        }
    }

    public static void main(String[] args) throws Exception {
        ServerConfig config = ServerConfig.load(args);
        RecordCollectorServer server = new RecordCollectorServer(config);

        Runtime.getRuntime().addShutdownHook(new Thread(server::stop));

        server.start();
        server.serverChannel.closeFuture().sync();
    }
}
