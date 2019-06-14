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
package com.azure.data.cosmos;

import com.azure.data.cosmos.internal.HttpConstants;
import com.azure.data.cosmos.internal.Paths;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public class CosmosContainer extends CosmosResource {

    private CosmosDatabase database;

    CosmosContainer(String id, CosmosDatabase database) {
        super(id);
        this.database = database;
    }

    AsyncDocumentClient getContextClient() {
        if(this.database == null || this.database.getClient() == null) {
            return null;
        }

        return this.database.getClient().getContextClient();
    }

    /**
     * Gets the context client.
     *
     * @param cosmosContainer the container client.
     * @return the context client.
     */
    public static AsyncDocumentClient getContextClient(CosmosContainer cosmosContainer) {
        if(cosmosContainer == null) {
            return null;
        }

        return cosmosContainer.getContextClient();
    }

    /**
     * Gets the self link to the container.
     *
     * @param cosmosContainer the container client.
     * @return the self link.
     */
    public static String getSelfLink(CosmosContainer cosmosContainer) {
        if(cosmosContainer == null) {
            return null;
        }

        return cosmosContainer.getLink();
    }

    /**
     * Reads the document container
     *
     * After subscription the operation will be performed.
     * The {@link Mono} upon successful completion will contain a single cosmos container response with the read
     * container.
     * In case of failure the {@link Mono} will error.
     *
     * @return an {@link Mono} containing the single cosmos container response with the read container or an error.
     */
    public Mono<CosmosContainerResponse> read() {
        return read(new CosmosContainerRequestOptions());
    }

    /**
     * Reads the document container by the container link.
     *
     * After subscription the operation will be performed.
     * The {@link Mono} upon successful completion will contain a single cosmos container response with the read container.
     * In case of failure the {@link Mono} will error.
     *
     * @param options        The cosmos container request options.
     * @return an {@link Mono} containing the single cosmos container response with the read container or an error.
     */
    public Mono<CosmosContainerResponse> read(CosmosContainerRequestOptions options) {
        if (options == null) {
            options = new CosmosContainerRequestOptions();
        }
        return database.getDocClientWrapper().readCollection(getLink(), options.toRequestOptions())
                .map(response -> new CosmosContainerResponse(response, database)).single();
    }

    /**
     * Deletes the item container
     *
     * After subscription the operation will be performed.
     * The {@link Mono} upon successful completion will contain a single cosmos container response for the deleted database.
     * In case of failure the {@link Mono} will error.
     *
     * @param options        the request options.
     * @return an {@link Mono} containing the single cosmos container response for the deleted database or an error.
     */
    public Mono<CosmosContainerResponse> delete(CosmosContainerRequestOptions options) {
        if (options == null) {
            options = new CosmosContainerRequestOptions();
        }
        return database.getDocClientWrapper()
                                                 .deleteCollection(getLink(), options.toRequestOptions())
                                                 .map(response -> new CosmosContainerResponse(response, database))
                                                 .single();
    }

    /**
     * Deletes the item container
     *
     * After subscription the operation will be performed.
     * The {@link Mono} upon successful completion will contain a single cosmos container response for the deleted container.
     * In case of failure the {@link Mono} will error.
     *
     * @return an {@link Mono} containing the single cosmos container response for the deleted container or an error.
     */
    public Mono<CosmosContainerResponse> delete() {
        return delete(new CosmosContainerRequestOptions());
    }

    /**
     * Replaces a document container.
     *
     * After subscription the operation will be performed.
     * The {@link Mono} upon successful completion will contain a single cosmos container response with the replaced document container.
     * In case of failure the {@link Mono} will error.
     *
     * @param containerSettings the item container settings
     * @param options    the cosmos container request options.
     * @return an {@link Mono} containing the single cosmos container response with the replaced document container or an error.
     */
    public Mono<CosmosContainerResponse> replace(CosmosContainerSettings containerSettings,
                                                   CosmosContainerRequestOptions options) {
        validateResource(containerSettings);
        if(options == null){
            options = new CosmosContainerRequestOptions();
        }
        return database.getDocClientWrapper()
                                                 .replaceCollection(containerSettings.getV2Collection(),options.toRequestOptions())
                                                 .map(response -> new CosmosContainerResponse(response, database))
                                                 .single();
    }

    /* CosmosItem operations */

    /**
     * Creates a cosmos item.
     *
     * After subscription the operation will be performed.
     * The {@link Mono} upon successful completion will contain a single resource response with the created cosmos item.
     * In case of failure the {@link Mono} will error.
     *
     * @param item the cosmos item represented as a POJO or cosmos item object.
     * @return an {@link Mono} containing the single resource response with the created cosmos item or an error.
     */
    public Mono<CosmosItemResponse> createItem(Object item){
        return createItem(item, new CosmosItemRequestOptions());
    }

    /**
     * Creates a cosmos item.
     *
     * After subscription the operation will be performed.
     * The {@link Mono} upon successful completion will contain a single resource response with the created cosmos item.
     * In case of failure the {@link Mono} will error.
     *
     * @param item the cosmos item represented as a POJO or cosmos item object.
     * @param partitionKey the partition key
     * @return an {@link Mono} containing the single resource response with the created cosmos item or an error.
     */
    public Mono<CosmosItemResponse> createItem(Object item, Object partitionKey){
        return createItem(item, new CosmosItemRequestOptions(partitionKey));
    }

    /**
     * Creates a cosmos item.
     *
     * After subscription the operation will be performed.
     * The {@link Mono} upon successful completion will contain a single resource response with the created cosmos item.
     * In case of failure the {@link Mono} will error.
     *
     * @param item                         the cosmos item represented as a POJO or cosmos item object.
     * @param options                      the request options.
     * @return an {@link Mono} containing the single resource response with the created cosmos item or an error.
     */
    public Mono<CosmosItemResponse> createItem(Object item, CosmosItemRequestOptions options) {
        if (options == null) {
            options = new CosmosItemRequestOptions();
        }
        RequestOptions requestOptions = options.toRequestOptions();
        return database.getDocClientWrapper()
                                                 .createDocument(getLink(),
                                                         CosmosItemProperties.fromObject(item),
                                                         requestOptions,
                                                         true)
                                                 .map(response -> new CosmosItemResponse(response,
                                                         requestOptions.getPartitionKey(),
                                                         this))
                                                 .single();
    }

    /**
     * Upserts an item.
     *
     * After subscription the operation will be performed.
     * The {@link Mono} upon successful completion will contain a single resource response with the upserted item.
     * In case of failure the {@link Mono} will error.
     *
     * @param item                         the item represented as a POJO or Item object to upsert.
     * @return an {@link Mono} containing the single resource response with the upserted document or an error.
     */
    public Mono<CosmosItemResponse> upsertItem(Object item) {
        return upsertItem(item, new CosmosItemRequestOptions());
    }

    /**
     * Upserts an item.
     *
     * After subscription the operation will be performed.
     * The {@link Mono} upon successful completion will contain a single resource response with the upserted item.
     * In case of failure the {@link Mono} will error.
     *
     * @param item                         the item represented as a POJO or Item object to upsert.
     * @param partitionKey                 the partitionKey to be used.
     * @return an {@link Mono} containing the single resource response with the upserted document or an error.
     */
    public Mono<CosmosItemResponse> upsertItem(Object item, Object partitionKey) {
        return upsertItem(item, new CosmosItemRequestOptions(partitionKey));
    }

    /**
     * Upserts a cosmos item.
     *
     * After subscription the operation will be performed. 
     * The {@link Mono} upon successful completion will contain a single resource response with the upserted item.
     * In case of failure the {@link Mono} will error.
     *
     * @param item                         the item represented as a POJO or Item object to upsert.
     * @param options                      the request options.
     * @return an {@link Mono} containing the single resource response with the upserted document or an error.
     */
    public Mono<CosmosItemResponse> upsertItem(Object item, CosmosItemRequestOptions options){
        if (options == null) {
            options = new CosmosItemRequestOptions();
        }
        RequestOptions requestOptions = options.toRequestOptions();
        
        return this.getDatabase()
                                                                            .getDocClientWrapper()
                                                                            .upsertDocument(this.getLink(),
                                                                                            CosmosItemProperties.fromObject(item),
                                                                                            options.toRequestOptions(),
                                                                                            true)
                                                                            .map(response -> new CosmosItemResponse(response,
                                                                                    requestOptions.getPartitionKey(),
                                                                                    this))
                                                                            .single();
    }

    /**
     * Reads all cosmos items in the container.
     *
     * After subscription the operation will be performed.
     * The {@link Flux} will contain one or several feed response of the read cosmos items.
     * In case of failure the {@link Flux} will error.
     *
     * @return an {@link Flux} containing one or several feed response pages of the read cosmos items or an error.
     */
    public Flux<FeedResponse<CosmosItemProperties>> listItems() {
        return listItems(new FeedOptions());
    }

    /**
     * Reads all cosmos items in a container.
     *
     * After subscription the operation will be performed.
     * The {@link Flux} will contain one or several feed response of the read cosmos items.
     * In case of failure the {@link Flux} will error.
     *
     * @param options        the feed options.
     * @return an {@link Flux} containing one or several feed response pages of the read cosmos items or an error.
     */
    public Flux<FeedResponse<CosmosItemProperties>> listItems(FeedOptions options) {
        return getDatabase().getDocClientWrapper()
                                                   .readDocuments(getLink(), options)
                                                   .map(response-> BridgeInternal.createFeedResponse(CosmosItemProperties.getFromV2Results(response.results()),
                                                           response.responseHeaders()));
    }

    /**
     * Query for documents in a items in a container
     *
     * After subscription the operation will be performed. 
     * The {@link Flux} will contain one or several feed response of the obtained items.
     * In case of failure the {@link Flux} will error.
     *
     * @param query          the query.
     * @param options        the feed options.
     * @return an {@link Flux} containing one or several feed response pages of the obtained items or an error.
     */
    public Flux<FeedResponse<CosmosItemProperties>> queryItems(String query, FeedOptions options){
        return queryItems(new SqlQuerySpec(query), options);
    }

    /**
     * Query for documents in a items in a container
     *
     * After subscription the operation will be performed. 
     * The {@link Flux} will contain one or several feed response of the obtained items.
     * In case of failure the {@link Flux} will error.
     *
     * @param querySpec      the SQL query specification.
     * @param options        the feed options.
     * @return an {@link Flux} containing one or several feed response pages of the obtained items or an error.
     */
    public Flux<FeedResponse<CosmosItemProperties>> queryItems(SqlQuerySpec querySpec, FeedOptions options){
        return getDatabase()
                                                   .getDocClientWrapper()
                                                   .queryDocuments(getLink(), querySpec, options)
                                                   .map(response-> BridgeInternal.createFeedResponseWithQueryMetrics(
                                                           CosmosItemProperties.getFromV2Results(response.results()),
                                                           response.responseHeaders(),
                                                           response.queryMetrics()));
    }

    /**
     * Query for documents in a items in a container
     *
     * After subscription the operation will be performed.
     * The {@link Flux} will contain one or several feed response of the obtained items.
     * In case of failure the {@link Flux} will error.
     *
     * @param changeFeedOptions        the feed options.
     * @return an {@link Flux} containing one or several feed response pages of the obtained items or an error.
     */
    public Flux<FeedResponse<CosmosItemProperties>> queryChangeFeedItems(ChangeFeedOptions changeFeedOptions){
        return getDatabase()
                .getDocClientWrapper()
                .queryDocumentChangeFeed(getLink(), changeFeedOptions)
                .map(response-> BridgeInternal.createFeedResponseWithQueryMetrics(
                    CosmosItemProperties.getFromV2Results(response.results()),
                    response.responseHeaders(), response.queryMetrics()));
    }

    /**
     * Gets a CosmosItem object without making a service call
     *
     * @param id           id of the item
     * @param partitionKey the partition key
     * @return a cosmos item
     */
    public CosmosItem getItem(String id, Object partitionKey){
        return new CosmosItem(id, partitionKey, this);
    }

    /* CosmosStoredProcedure operations */

    /**
     * Creates a cosmos stored procedure.
     *
     * After subscription the operation will be performed.
     * The {@link Mono} upon successful completion will contain a single cosmos stored procedure response with the
     * created cosmos stored procedure.
     * In case of failure the {@link Mono} will error.
     *
     * @param settings  the cosmos stored procedure settings.
     * @return an {@link Mono} containing the single cosmos stored procedure resource response or an error.
     */
    public Mono<CosmosStoredProcedureResponse> createStoredProcedure(CosmosStoredProcedureSettings settings){
        return this.createStoredProcedure(settings, new CosmosStoredProcedureRequestOptions());
    }

    /**
     * Creates a cosmos stored procedure.
     *
     * After subscription the operation will be performed.
     * The {@link Mono} upon successful completion will contain a single cosmos stored procedure response with the
     * created cosmos stored procedure.
     * In case of failure the {@link Mono} will error.
     *
     * @param settings  the cosmos stored procedure settings.
     * @param options the stored procedure request options.
     * @return an {@link Mono} containing the single cosmos stored procedure resource response or an error.
     */
    public Mono<CosmosStoredProcedureResponse> createStoredProcedure(CosmosStoredProcedureSettings settings,
                                                                       CosmosStoredProcedureRequestOptions options){
        if(options == null){
            options = new CosmosStoredProcedureRequestOptions();
        }
        StoredProcedure sProc = new StoredProcedure();
        sProc.id(settings.id());
        sProc.setBody(settings.body());
        return database.getDocClientWrapper()
                                                 .createStoredProcedure(getLink(), sProc, options.toRequestOptions())
                                                 .map(response -> new CosmosStoredProcedureResponse(response, this))
                                                 .single();
    }

    /**
     * Reads all cosmos stored procedures in a container.
     *
     * After subscription the operation will be performed.
     * The {@link Flux} will contain one or several feed response pages of the read cosmos stored procedure settings.
     * In case of failure the {@link Flux} will error.
     *
     * @param options        the feed options.
     * @return an {@link Flux} containing one or several feed response pages of the read cosmos stored procedures
     * settings or an error.
     */
    public Flux<FeedResponse<CosmosStoredProcedureSettings>> listStoredProcedures(FeedOptions options){
        return database.getDocClientWrapper()
                                                   .readStoredProcedures(getLink(), options)
                                                   .map(response -> BridgeInternal.createFeedResponse(CosmosStoredProcedureSettings.getFromV2Results(response.results()),
                                                                                                      response.responseHeaders()));
    }

    /**
     * Query for stored procedures in a container.
     *
     * After subscription the operation will be performed. 
     * The {@link Flux} will contain one or several feed response pages of the obtained stored procedures.
     * In case of failure the {@link Flux} will error.
     *
     * @param query      the the query.
     * @param options    the feed options.
     * @return an {@link Flux} containing one or several feed response pages of the obtained stored procedures or
     * an error.
     */
    public Flux<FeedResponse<CosmosStoredProcedureSettings>> queryStoredProcedures(String query,
                                                                                       FeedOptions options){
        return queryStoredProcedures(new SqlQuerySpec(query), options);
    }

    /**
     * Query for stored procedures in a container.
     *
     * After subscription the operation will be performed. 
     * The {@link Flux} will contain one or several feed response pages of the obtained stored procedures.
     * In case of failure the {@link Flux} will error.
     *
     * @param querySpec  the SQL query specification.
     * @param options    the feed options.
     * @return an {@link Flux} containing one or several feed response pages of the obtained stored procedures or
     * an error.
     */
    public Flux<FeedResponse<CosmosStoredProcedureSettings>> queryStoredProcedures(SqlQuerySpec querySpec,
                                                                                       FeedOptions options){
        return database.getDocClientWrapper()
                                                   .queryStoredProcedures(getLink(), querySpec,options)
                                                   .map(response -> BridgeInternal.createFeedResponse( CosmosStoredProcedureSettings.getFromV2Results(response.results()),
                                                                                                       response.responseHeaders()));
    }

    /**
     * Gets a CosmosStoredProcedure object without making a service call
     * @param id id of the stored procedure
     * @return a cosmos stored procedure
     */
    public CosmosStoredProcedure getStoredProcedure(String id){
        return new CosmosStoredProcedure(id, this);
    }


    /* UDF Operations */

    /**
     * Creates a cosmos user defined function.
     *
     * After subscription the operation will be performed.
     * The {@link Mono} upon successful completion will contain a single cosmos user defined function response.
     * In case of failure the {@link Mono} will error.
     *
     * @param settings       the cosmos user defined function settings
     * @param options        the cosmos request options.
     * @return an {@link Mono} containing the single resource response with the created user defined function or an error.
     */
    public Mono<CosmosUserDefinedFunctionResponse> createUserDefinedFunction(CosmosUserDefinedFunctionSettings settings,
                                                                               CosmosRequestOptions options){
        UserDefinedFunction udf = new UserDefinedFunction();
        udf.id(settings.id());
        udf.setBody(settings.body());
        if(options == null){
            options = new CosmosRequestOptions();
        }
        return database.getDocClientWrapper()
                                                 .createUserDefinedFunction(getLink(), udf, options.toRequestOptions())
                                                 .map(response -> new CosmosUserDefinedFunctionResponse(response, this)).single();
    }

    /**
     * Reads all cosmos user defined functions in the container
     *
     * After subscription the operation will be performed.
     * The {@link Flux} will contain one or several feed response pages of the read user defined functions.
     * In case of failure the {@link Flux} will error.
     *
     * @param options        the feed options.
     * @return an {@link Flux} containing one or several feed response pages of the read user defined functions or an error.
     */
    public Flux<FeedResponse<CosmosUserDefinedFunctionSettings>> listUserDefinedFunctions(FeedOptions options){
        return database.getDocClientWrapper()
                                                   .readUserDefinedFunctions(getLink(), options)
                                                   .map(response -> BridgeInternal.createFeedResponse(CosmosUserDefinedFunctionSettings.getFromV2Results(response.results()),
                                                                                                      response.responseHeaders()));
    }

    /**
     * Query for user defined functions in the container.
     *
     * After subscription the operation will be performed. 
     * The {@link Flux} will contain one or several feed response pages of the obtained user defined functions.
     * In case of failure the {@link Flux} will error.
     *
     * @param query          the query.
     * @param options        the feed options.
     * @return an {@link Flux} containing one or several feed response pages of the obtained user defined functions or an error.
     */
    public Flux<FeedResponse<CosmosUserDefinedFunctionSettings>> queryUserDefinedFunctions(String query,
                                                                                               FeedOptions options){
        return queryUserDefinedFunctions(new SqlQuerySpec(query), options);
    }

    /**
     * Query for user defined functions in the container.
     *
     * After subscription the operation will be performed. 
     * The {@link Flux} will contain one or several feed response pages of the obtained user defined functions.
     * In case of failure the {@link Flux} will error.
     *
     * @param querySpec      the SQL query specification.
     * @param options        the feed options.
     * @return an {@link Flux} containing one or several feed response pages of the obtained user defined functions or an error.
     */
    public Flux<FeedResponse<CosmosUserDefinedFunctionSettings>> queryUserDefinedFunctions(SqlQuerySpec querySpec,
                                                                                               FeedOptions options){
        return database.getDocClientWrapper()
                                                   .queryUserDefinedFunctions(getLink(),querySpec, options)
                                                   .map(response -> BridgeInternal.createFeedResponse(CosmosUserDefinedFunctionSettings.getFromV2Results(response.results()),
                                                                                                      response.responseHeaders()));
    }

    /**
     * Gets a CosmosUserDefinedFunction object without making a service call
     * @param id id of the user defined function
     * @return a cosmos user defined function
     */
    public CosmosUserDefinedFunction getUserDefinedFunction(String id){
        return new CosmosUserDefinedFunction(id, this);
    }

    /* Trigger Operations */
    /**
     * Creates a Cosmos trigger.
     *
     * After subscription the operation will be performed.
     * The {@link Mono} upon successful completion will contain a cosmos trigger response
     * In case of failure the {@link Mono} will error.
     *
     * @param settings the cosmos trigger settings
     * @param options        the request options.
     * @return an {@link Mono} containing the single resource response with the created trigger or an error.
     */
    public Mono<CosmosTriggerResponse> createTrigger(CosmosTriggerSettings settings,
                                                       CosmosRequestOptions options){
        Trigger trigger = new Trigger(settings.toJson());
        if(options == null){
            options = new CosmosRequestOptions();
        }
        return database.getDocClientWrapper()
                                                 .createTrigger(getLink(), trigger,options.toRequestOptions())
                                                 .map(response -> new CosmosTriggerResponse(response, this))
                                                 .single();
    }

    /**
     * Reads all triggers in a container
     *
     * After subscription the operation will be performed.
     * The {@link Flux} will contain one or several feed response pages of the read cosmos trigger settings.
     * In case of failure the {@link Flux} will error.
     *
     * @param options        the feed options.
     * @return an {@link Flux} containing one or several feed response pages of the read cosmos rigger settings or an error.
     */
    public Flux<FeedResponse<CosmosTriggerSettings>> listTriggers(FeedOptions options){
        return database.getDocClientWrapper()
                                                   .readTriggers(getLink(), options)
                                                   .map(response -> BridgeInternal.createFeedResponse(CosmosTriggerSettings.getFromV2Results(response.results()),
                                                                                                      response.responseHeaders()));
    }

    /**
     * Query for triggers in the container
     *
     * After subscription the operation will be performed. 
     * The {@link Flux} will contain one or several feed response pages of the obtained triggers.
     * In case of failure the {@link Flux} will error.
     *
     * @param query          the query.
     * @param options        the feed options.
     * @return an {@link Flux} containing one or several feed response pages of the obtained triggers or an error.
     */
    public Flux<FeedResponse<CosmosTriggerSettings>> queryTriggers(String query, FeedOptions options){
        return queryTriggers(new SqlQuerySpec(query), options);
    }

    /**
     * Query for triggers in the container
     *
     * After subscription the operation will be performed. 
     * The {@link Flux} will contain one or several feed response pages of the obtained triggers.
     * In case of failure the {@link Flux} will error.
     *
     * @param querySpec      the SQL query specification.
     * @param options        the feed options.
     * @return an {@link Flux} containing one or several feed response pages of the obtained triggers or an error.
     */
    public Flux<FeedResponse<CosmosTriggerSettings>> queryTriggers(SqlQuerySpec querySpec,
                                                     FeedOptions options){
        return database.getDocClientWrapper()
                                                   .queryTriggers(getLink(), querySpec, options)
                                                   .map(response -> BridgeInternal.createFeedResponse(CosmosTriggerSettings.getFromV2Results(response.results()),
                                                                                                      response.responseHeaders()));
    }

    /**
     * Gets a CosmosTrigger object without making a service call
     * @param id id of the cosmos trigger
     * @return a cosmos trigger
     */
    public CosmosTrigger getTrigger(String id){
        return new CosmosTrigger(id, this);
    }

    /**
     * Gets the throughput of the container
     *
     * @return a {@link Mono} containing throughput or an error.
     */
    public Mono<Integer> readProvisionedThroughput(){
        return this.read()
                .flatMap(cosmosContainerResponse ->
                        database.getDocClientWrapper()
                                        .queryOffers("select * from c where c.offerResourceId = '" +
                                                cosmosContainerResponse.resourceSettings().resourceId()
                                                + "'", new FeedOptions()).single())
                                .flatMap(offerFeedResponse -> {
                                    if(offerFeedResponse.results().isEmpty()){
                                        return Mono.error(new CosmosClientException(HttpConstants.StatusCodes.BADREQUEST,
                                                "No offers found for the resource"));
                                    }
                                    return database.getDocClientWrapper()
                                                    .readOffer(offerFeedResponse.results()
                                                            .get(0)
                                                            .selfLink()).single();
                                })
                                .map(cosmosOfferResponse -> cosmosOfferResponse
                                        .getResource()
                                        .getThroughput());
    }

    /**
     * Sets throughput provisioned for a container in measurement of Requests-per-Unit in the Azure Cosmos service.
     *
     * @param requestUnitsPerSecond the cosmos container throughput, expressed in Request Units per second
     * @return a {@link Mono} containing throughput or an error.
     */
    public Mono<Integer> replaceProvisionedThroughputAsync(int requestUnitsPerSecond){
        return this.read()
                .flatMap(cosmosContainerResponse ->
                        database.getDocClientWrapper()
                                        .queryOffers("select * from c where c.offerResourceId = '" +
                                                cosmosContainerResponse.resourceSettings().resourceId()
                                                + "'", new FeedOptions()).single())
                                .flatMap(offerFeedResponse -> {
                                    if(offerFeedResponse.results().isEmpty()){
                                        return Mono.error(new CosmosClientException(HttpConstants.StatusCodes.BADREQUEST,
                                                "No offers found for the resource"));
                                    }
                                    Offer offer = offerFeedResponse.results().get(0);
                                    offer.setThroughput(requestUnitsPerSecond);
                                    return database.getDocClientWrapper()
                                                    .replaceOffer(offer).single();
                                }).map(offerResourceResponse -> offerResourceResponse.getResource().getThroughput());
    }

    /**
     * Gets the parent Database
     *
     * @return the (@link CosmosDatabase)
     */
    public CosmosDatabase getDatabase() {
        return database;
    }

    @Override
    protected String URIPathSegment() {
        return Paths.COLLECTIONS_PATH_SEGMENT;
    }

    @Override
    protected String parentLink() {
        return database.getLink();
    }

}