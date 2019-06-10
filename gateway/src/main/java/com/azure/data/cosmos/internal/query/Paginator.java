/*
 * The MIT License (MIT)
 * Copyright (c) 2018 Microsoft Corporation
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.azure.data.cosmos.internal.query;

import com.azure.data.cosmos.ChangeFeedOptions;
import com.azure.data.cosmos.FeedOptions;
import com.azure.data.cosmos.FeedOptionsBase;
import com.azure.data.cosmos.FeedResponse;
import com.azure.data.cosmos.Resource;
import com.azure.data.cosmos.internal.RxDocumentServiceRequest;
import hu.akarnokd.rxjava.interop.RxJavaInterop;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.adapter.rxjava.RxJava2Adapter;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.publisher.SynchronousSink;
import rx.Observable;
import rx.Observer;
import rx.observables.AsyncOnSubscribe;

import java.util.concurrent.Callable;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * While this class is public, but it is not part of our published public APIs.
 * This is meant to be internally used only by our sdk.
 */
public class Paginator {

    private final static Logger logger = LoggerFactory.getLogger(Paginator.class);

    public static <T extends Resource> Flux<FeedResponse<T>> getPaginatedChangeFeedQueryResultAsObservable(
            ChangeFeedOptions feedOptions, BiFunction<String, Integer, RxDocumentServiceRequest> createRequestFunc,
            Function<RxDocumentServiceRequest, Flux<FeedResponse<T>>> executeFunc, Class<T> resourceType,
            int maxPageSize) {
        return getPaginatedQueryResultAsObservable(feedOptions, createRequestFunc, executeFunc, resourceType,
                -1, maxPageSize, true);
    }

    public static <T extends Resource> Flux<FeedResponse<T>> getPaginatedQueryResultAsObservable(
            FeedOptions feedOptions,
            BiFunction<String, Integer, RxDocumentServiceRequest> createRequestFunc,
            Function<RxDocumentServiceRequest, Flux<FeedResponse<T>>> executeFunc, Class<T> resourceType,
            int maxPageSize) {
        return getPaginatedQueryResultAsObservable(feedOptions, createRequestFunc, executeFunc, resourceType,
                -1, maxPageSize);
    }

    public static <T extends Resource> Flux<FeedResponse<T>> getPaginatedQueryResultAsObservable(
            FeedOptions options,
            BiFunction<String, Integer, RxDocumentServiceRequest> createRequestFunc,
            Function<RxDocumentServiceRequest, Flux<FeedResponse<T>>> executeFunc, Class<T> resourceType,
            int top, int maxPageSize) {
        return getPaginatedQueryResultAsObservable(options, createRequestFunc, executeFunc, resourceType,
                top, maxPageSize, false);
    }

    private static <T extends Resource> Flux<FeedResponse<T>> getPaginatedQueryResultAsObservable(
            FeedOptionsBase options,
            BiFunction<String, Integer, RxDocumentServiceRequest> createRequestFunc,
            Function<RxDocumentServiceRequest, Flux<FeedResponse<T>>> executeFunc, Class<T> resourceType,
            int top, int maxPageSize, boolean isChangeFeed) {

        Fetcher<T> fetcher = new Fetcher<>(createRequestFunc, executeFunc, options, isChangeFeed, top, maxPageSize);

        Flux<Flux<FeedResponse<T>>> fluxSink = Flux.create((Consumer<FluxSink<Flux<FeedResponse<T>>>>) feedResponseFluxSink -> {
            if (fetcher.shouldFetchMore()) {
                Flux<FeedResponse<T>> nextPage = fetcher.nextPage();
                feedResponseFluxSink.next(nextPage);
            } else {
                logger.debug("No more results");
            }
            feedResponseFluxSink.complete();
        }).repeat(fetcher::shouldFetchMore);

        return Flux.merge(fluxSink);

//        //  TODO: Remove this commented code after the above implementation is complete
//        Observable<FeedResponse<T>> obs = Observable.defer(() -> {
//            return Observable.create(new AsyncOnSubscribe<Fetcher, FeedResponse<T>>() {
//                @Override
//                protected Fetcher generateState() {
//                    return new Fetcher(createRequestFunc, executeFunc, options, isChangeFeed, top, maxPageSize);
//                }
//
//                @Override
//                protected Fetcher next(Fetcher fetcher, long requested, Observer<Observable<? extends FeedResponse<T>>> observer) {
//                    assert requested == 1 : "requested amount expected to be 1"; // as there is a rebatchRequests(1)
//
//                    if (fetcher.shouldFetchMore()) {
//                        Observable<FeedResponse<T>> respObs = RxJavaInterop.<FeedResponse<T>>toV1Observable(fetcher.nextPage());
//                        observer.onNext(respObs);
//                    } else {
//                        logger.debug("No more results");
//                        observer.onCompleted();
//                    }
//
//                    return fetcher;
//                }
//            }).rebatchRequests(1);
//        });
//
//        return RxJava2Adapter.flowableToFlux(RxJavaInterop.toV2Flowable(obs));
    }
}