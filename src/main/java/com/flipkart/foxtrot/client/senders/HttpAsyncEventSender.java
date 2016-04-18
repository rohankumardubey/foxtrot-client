package com.flipkart.foxtrot.client.senders;

import com.flipkart.foxtrot.client.Document;
import com.flipkart.foxtrot.client.EventSender;
import com.flipkart.foxtrot.client.FoxtrotClientConfig;
import com.flipkart.foxtrot.client.cluster.FoxtrotCluster;
import com.flipkart.foxtrot.client.cluster.FoxtrotClusterMember;
import com.flipkart.foxtrot.client.selectors.FoxtrotTarget;
import com.flipkart.foxtrot.client.serialization.EventSerializationHandler;
import com.flipkart.foxtrot.client.serialization.SerializationException;
import com.google.common.base.Preconditions;
import com.google.common.util.concurrent.*;
import feign.Feign;
import feign.Response;
import feign.jackson.JacksonDecoder;
import feign.jackson.JacksonEncoder;
import feign.okhttp.OkHttpClient;
import feign.slf4j.Slf4jLogger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

public class HttpAsyncEventSender extends EventSender {

    private static final Logger logger = LoggerFactory.getLogger(HttpAsyncEventSender.class.getSimpleName());


    private final static JacksonDecoder decoder = new JacksonDecoder();
    private final static JacksonEncoder encoder = new JacksonEncoder();
    private final static Slf4jLogger slf4jLogger = new Slf4jLogger();

    private String table;
    private FoxtrotCluster client;
    private FoxtrotHttpClient httpClient;

    private ListeningExecutorService executorService = MoreExecutors.listeningDecorator(Executors.newCachedThreadPool());

    public HttpAsyncEventSender(final FoxtrotClientConfig config, FoxtrotCluster client, EventSerializationHandler serializationHandler)  {
        super(serializationHandler);
        this.table = config.getTable();
        this.client = client;
        this.httpClient = Feign.builder()
                .client(new OkHttpClient())
                .logger(slf4jLogger)
                .logLevel(feign.Logger.Level.BASIC)
                .target(new FoxtrotTarget<FoxtrotHttpClient>(FoxtrotHttpClient.class, "foxtrot", client));
    }

    @Override
    public void send(Document document) {
        send(Collections.singletonList(document));
    }

    @Override
    public void send(List<Document> documents) {
        try {
            send(getSerializationHandler().serialize(documents));
        } catch (SerializationException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void close() throws Exception {

    }

    public void send(final byte[] payload) {
        final FoxtrotClusterMember clusterMember = client.member();
        Preconditions.checkNotNull(clusterMember, "No members found in foxtrot cluster");
        ListenableFuture<Response> response = executorService.submit(new Callable<Response>() {
            @Override
            public Response call() throws Exception {
                return httpClient.send(table, payload);
            }
        });
        Futures.addCallback(response, new FutureCallback<Response>() {
            @Override
            public void onSuccess(Response response) {
                logger.debug("table={} messages_sent host={} port={}", table, clusterMember.getHost(), clusterMember.getPort());
            }

            @Override
            public void onFailure(Throwable throwable) {
                logger.error("table={} message_sending_failed", new Object[]{table}, throwable);
            }
        });
    }
}
