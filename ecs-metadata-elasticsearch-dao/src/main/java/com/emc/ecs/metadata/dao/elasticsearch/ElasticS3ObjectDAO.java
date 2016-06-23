/*
 * Copyright (c) 2016, EMC Corporation.
 * Redistribution and use in source and binary forms, with or without modification,
 * are permitted provided that the following conditions are met:
 *
 *     + Redistributions of source code must retain the above copyright notice,
 *       this list of conditions and the following disclaimer.
 *     + Redistributions in binary form must reproduce the above copyright
 *       notice, this list of conditions and the following disclaimer in the
 *       documentation and/or other materials provided with the distribution.
 *     + The name of EMC Corporation may not be used to endorse or promote
 *       products derived from this software without specific prior written
 *       permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED
 * TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR
 * PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS
 * BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */

package com.emc.ecs.metadata.dao.elasticsearch;


import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Iterator;
import java.util.Map.Entry;

import org.elasticsearch.action.admin.indices.create.CreateIndexRequest;
import org.elasticsearch.action.admin.indices.exists.indices.IndicesExistsRequest;
import org.elasticsearch.action.admin.indices.mapping.put.PutMappingResponse;
import org.elasticsearch.action.bulk.BulkRequestBuilder;
import org.elasticsearch.action.bulk.BulkResponse;
import org.elasticsearch.action.delete.DeleteRequest;
import org.elasticsearch.action.index.IndexRequestBuilder;
import org.elasticsearch.action.search.SearchRequestBuilder;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.client.transport.TransportClient;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.settings.Settings.Builder;
import org.elasticsearch.common.transport.InetSocketTransportAddress;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.elasticsearch.index.query.BoolQueryBuilder;
import org.elasticsearch.index.query.QueryBuilder;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.SearchHits;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.emc.ecs.metadata.dao.ObjectDAO;
import com.emc.object.s3.bean.AbstractVersion;
import com.emc.object.s3.bean.DeleteMarker;
import com.emc.object.s3.bean.ListObjectsResult;
import com.emc.object.s3.bean.ListVersionsResult;
import com.emc.object.s3.bean.QueryMetadata;
import com.emc.object.s3.bean.QueryObject;
import com.emc.object.s3.bean.QueryObjectsResult;
import com.emc.object.s3.bean.S3Object;
import com.emc.object.s3.bean.Version;


public class ElasticS3ObjectDAO implements ObjectDAO {

	
	private final static String CLIENT_SNIFFING_CONFIG       = "client.transport.sniff";
	private final static String CLIENT_CLUSTER_NAME_CONFIG   = "cluster.name";
	public  final static String S3_OBJECT_INDEX_NAME         = "ecs-s3-object";
	public  final static String S3_OBJECT_VERSION_INDEX_NAME = "ecs-s3-object-version";
	public  final static String S3_OBJECT_INDEX_TYPE         = "object-info";
	public  final static String S3_OBJECT_VERSION_INDEX_TYPE = "object-version-info";
	public  final static String COLLECTION_TIME			     = "collection_time";
	public  final static String ANALYZED_TAG                 = "_analyzed";
	public  final static String NOT_ANALYZED_INDEX           = "not_analyzed";
	public  final static String ANALYZED_INDEX               = "analyzed";
	
	
	public  final static String LAST_MODIFIED_TAG        = "last_modified";
	public  final static String SIZE_TAG                 = "size";
	public  final static String KEY_TAG                  = "key";
	public  final static String OWNER_ID_TAG             = "owner_id";
	public  final static String OWNER_NAME_TAG           = "owner_name";
	public  final static String NAMESPACE_TAG            = "namespace";
	public  final static String BUCKET_TAG               = "bucket";
	public  final static String ETAG_TAG                 = "e_tag";
	public  final static String VERSION_ID_TAG           = "version_id";
	public  final static String IS_LATEST_TAG            = "is_latest";
	public  final static String CUSTOM_GID_TAG           = "x-amz-meta-x-emc-posix-group-owner-name";
	public  final static String CUSTOM_UID_TAG           = "x-amz-meta-x-emc-posix-owner-name";
	public  final static String CUSTOM_MODIFIED_TIME_TAG = "mtime";
	
	
	//=========================
	// Private members
	//=========================
	private TransportClient elasticClient;
	private static Logger LOGGER = LoggerFactory.getLogger(ElasticS3ObjectDAO.class);
	
	private static final String            OLD_DATA_DATE_PATTERN = "yyyy-MM-dd";
	private static final SimpleDateFormat  OLD_DATA_DATE_FORMAT = new  SimpleDateFormat(OLD_DATA_DATE_PATTERN);
	
	//=========================
	// Public methods
	//=========================
	public ElasticS3ObjectDAO( ElasticDAOConfig config ) {
		
		try {
			
			Builder builder = Settings.settingsBuilder();
			
			// Check for new hosts within the cluster
			builder.put(CLIENT_SNIFFING_CONFIG, true);
			
			// specify cluster name
			if( config.getClusterName() != null ) {
				builder.put(CLIENT_CLUSTER_NAME_CONFIG, config.getClusterName());
			}
			
			Settings settings = builder.build();
			
			// create client
			elasticClient = TransportClient.builder().settings(settings).build();
			
			// add hosts
			for( String elasticHost : config.getHosts()) {
				elasticClient.addTransportAddress(new InetSocketTransportAddress(InetAddress.getByName(elasticHost), config.getPort()));				
			}
			
			// init S3 Object Index
			initS3ObjectIndex();
			
			// init S3 Object Version Index
			initS3ObjectVersionIndex();
			
			
		} catch (UnknownHostException e) {
			throw new RuntimeException(e.getLocalizedMessage());
		}				
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public void insert(ListObjectsResult listObjectsResult, String namespace, String bucket, Date collectionTime) {
		
		if( listObjectsResult == null ||
		    listObjectsResult.getObjects() == null ||
		    listObjectsResult.getObjects().isEmpty()  ) {
			
			// nothing to insert
			return;
		}
		
		BulkRequestBuilder requestBuilder = elasticClient.prepareBulk();
		
		// Generate JSON for object buckets info
		for( S3Object s3Object : listObjectsResult.getObjects() ) {
			XContentBuilder s3ObjectBuilder = toJsonFormat(s3Object, namespace, bucket, collectionTime);

			IndexRequestBuilder request = elasticClient.prepareIndex()
					.setIndex(S3_OBJECT_INDEX_NAME)
					.setType(S3_OBJECT_INDEX_TYPE)
					.setSource(s3ObjectBuilder);
			requestBuilder.add(request);
		}
		
		BulkResponse bulkResponse = requestBuilder.execute().actionGet();
	    int items = bulkResponse.getItems().length;
	    
		LOGGER.info( "Took " + bulkResponse.getTookInMillis() + " ms to index [" + items + "] items in Elasticsearch " + "index: " + 
					 S3_OBJECT_INDEX_NAME + " index type: " +  S3_OBJECT_INDEX_TYPE ); 
    
		if( bulkResponse.hasFailures() ) {
			LOGGER.error( "Failure(s) occured while items in Elasticsearch " + "index: " + 
							S3_OBJECT_INDEX_NAME + " index type: " +  S3_OBJECT_INDEX_TYPE );
		}
	}
	
	/**
	 * {@inheritDoc}
	 */
	@Override
	public void insert( QueryObjectsResult queryObjectsResult, String namespace,
						String bucketName, Date collectionTime ) {
		
		if( queryObjectsResult == null ||
			queryObjectsResult.getObjects() == null ||
			queryObjectsResult.getObjects().isEmpty()  ) {
				
			// nothing to insert
			return;
		}
		
		
		BulkRequestBuilder requestBuilder = elasticClient.prepareBulk();
		
		// Generate JSON for object buckets info
		for( QueryObject queryObject : queryObjectsResult.getObjects() ) {
			XContentBuilder s3ObjectBuilder = toJsonFormat(queryObject, namespace, bucketName, collectionTime);

			IndexRequestBuilder request = elasticClient.prepareIndex()
					.setIndex(S3_OBJECT_INDEX_NAME)
					.setType(S3_OBJECT_INDEX_TYPE)
					.setSource(s3ObjectBuilder);
			requestBuilder.add(request);
		}
		
		BulkResponse bulkResponse = requestBuilder.execute().actionGet();
	    int items = bulkResponse.getItems().length;
		LOGGER.info( "Took " + bulkResponse.getTookInMillis() + " ms to index [" + items + "] items in Elasticsearch " + "index: " + 
				 S3_OBJECT_INDEX_NAME + " index type: " +  S3_OBJECT_INDEX_TYPE ); 

		if( bulkResponse.hasFailures() ) {
			LOGGER.error( "Failure(s) occured while items in Elasticsearch " + "index: " + 
						S3_OBJECT_INDEX_NAME + " index type: " +  S3_OBJECT_INDEX_TYPE );
		}
	}
	
	/**
	 * {@inheritDoc}
	 */
	@Override
	public void insert(ListVersionsResult listVersionsResult, String namespace,
			           String bucketName, Date collectionTime) {
		
		if( listVersionsResult == null ||
			listVersionsResult.getVersions() == null ||
			listVersionsResult.getVersions().isEmpty()  ) {
					
			// nothing to insert
			return;
		}
		
		BulkRequestBuilder requestBuilder = elasticClient.prepareBulk();
		
		// Generate JSON for object version info
		for( AbstractVersion abstractVersion : listVersionsResult.getVersions() ) {
			if(abstractVersion instanceof Version) {
				XContentBuilder s3ObjectVersionBuilder = toJsonFormat((Version)abstractVersion, namespace, bucketName, collectionTime);
				
				IndexRequestBuilder request = elasticClient.prepareIndex()
			                .setIndex(S3_OBJECT_VERSION_INDEX_NAME)
			                .setType(S3_OBJECT_VERSION_INDEX_TYPE)
			                .setSource(s3ObjectVersionBuilder);
			    requestBuilder.add(request);
				
			} else if(abstractVersion instanceof DeleteMarker) {
				XContentBuilder s3ObjectVersionBuilder = toJsonFormat((DeleteMarker)abstractVersion, namespace, bucketName, collectionTime);
				
				IndexRequestBuilder request = elasticClient.prepareIndex()
		                .setIndex(S3_OBJECT_VERSION_INDEX_NAME)
		                .setType(S3_OBJECT_VERSION_INDEX_TYPE)
		                .setSource(s3ObjectVersionBuilder);
		        requestBuilder.add(request);
			}
		}
		
		BulkResponse bulkResponse = requestBuilder.execute().actionGet();
	    int items = bulkResponse.getItems().length;
	    
		LOGGER.info( "Took " + bulkResponse.getTookInMillis() + " ms to index [" + items + "] items in Elasticsearch " + "index: " + 
					 S3_OBJECT_VERSION_INDEX_NAME + " index type: " +  S3_OBJECT_VERSION_INDEX_TYPE ); 

		if( bulkResponse.hasFailures() ) {
			LOGGER.error( "Failure(s) occured while items in Elasticsearch " + "index: " + 
						  S3_OBJECT_VERSION_INDEX_NAME + " index type: " +  S3_OBJECT_VERSION_INDEX_TYPE );
		}
	}
	
	/**
	 * {@inheritDoc}
	 */
	@Override
	public Long purgeOldData(ObjectDataType type, Date thresholdDate) {
			
		switch(type) {
		  case object:
			// Purge old S3 Objects 
			return purgeIndex(thresholdDate, S3_OBJECT_INDEX_NAME, S3_OBJECT_INDEX_TYPE);
		  case object_versions:
			// Purge old S3 Object Versions
			return purgeIndex(thresholdDate, S3_OBJECT_VERSION_INDEX_NAME, S3_OBJECT_VERSION_INDEX_TYPE);
		  default:
			return 0L;
		}
	}
	
	/**
	 * Converts Object data in JSON format for Elasticsearch
	 * 
	 * @param s3Object
	 * @param namespace
	 * @param bucket
	 * @param collectionTime
	 * @return
	 */
	public static XContentBuilder toJsonFormat( S3Object s3Object, String namespace, String bucket, Date collectionTime ) {						
		return toJsonFormat(s3Object, namespace, bucket,collectionTime, null);
	}
	
	/**
	 * Converts Object data in JSON format for Elasticsearch
	 * 
	 * @param version
	 * @param namespace
	 * @param bucketName
	 * @param collectionTime
	 * @return
	 */
	public XContentBuilder toJsonFormat(Version version,
			String namespace, String bucketName, Date collectionTime) {
		
		return toJsonFormat( version, namespace, bucketName, collectionTime, null);
	}
	
	/**
	 * Converts Object data in JSON format for Elasticsearch
	 * 
	 * @param deleteMarker
	 * @param namespace
	 * @param bucketName
	 * @param collectionTime
	 * @return
	 */
	public XContentBuilder toJsonFormat(DeleteMarker deleteMarker,
			String namespace, String bucketName, Date collectionTime) {
		
		return toJsonFormat( deleteMarker, namespace, bucketName, collectionTime, null);
	}
	
	/**
	 * Converts Object data in JSON format for Elasticsearch
	 * 
	 * @param s3Object
	 * @param namespace
	 * @param bucket
	 * @param collectionTime
	 * @return
	 */
	public static XContentBuilder toJsonFormat( QueryObject s3Object, String namespace, String bucket, Date collectionTime ) {						
		return toJsonFormat(s3Object, namespace, bucket,collectionTime, null);
	}
	
	
	//=======================
	// Private methods
	//=======================

	/**
	 * Init Object index
	 */
	private void initS3ObjectIndex() {

		if (elasticClient
				.admin()
				.indices()
				.exists(new IndicesExistsRequest(S3_OBJECT_INDEX_NAME))
				.actionGet()
				.isExists()) {
			// Index already exists no need to re-create it
			return;
		}

		elasticClient.admin().indices().create(new CreateIndexRequest(S3_OBJECT_INDEX_NAME)).actionGet();	

		try {
			PutMappingResponse putMappingResponse = elasticClient.admin().indices()
					.preparePutMapping(S3_OBJECT_INDEX_NAME)
					.setType(S3_OBJECT_INDEX_TYPE)
					.setSource(XContentFactory.jsonBuilder().prettyPrint()
					  .startObject()
						.startObject(S3_OBJECT_INDEX_TYPE)
						
							// ========================================
							// Define how the basic fields are defined
							// ========================================
							.startObject("properties")
							// LAST_MODIFIED_TAG
							.startObject( LAST_MODIFIED_TAG ).field("type", "date")
								.field("format", "strict_date_optional_time||epoch_millis").endObject()
							// SIZE_TAG
							.startObject( SIZE_TAG ).field("type", "string").field("type", "long").endObject()
							// KEY_TAG
							.startObject( KEY_TAG ).field("type", "string")
								.field("index", NOT_ANALYZED_INDEX).endObject()
							// KEY_TAG Analyzed
							.startObject( KEY_TAG + ANALYZED_TAG).field("type", "string")
								.field("index", ANALYZED_INDEX).endObject()
							.startObject( ETAG_TAG ).field("type", "string")
								.field("index", NOT_ANALYZED_INDEX).endObject()	
							// NAMESPACE_TAG
							.startObject( NAMESPACE_TAG ).field("type", "string")
								.field("index", NOT_ANALYZED_INDEX).endObject()
							// BUCKET_TAG
							.startObject( BUCKET_TAG ).field("type", "string")
								.field("index", NOT_ANALYZED_INDEX).endObject()
							// OWNER_ID_TAG
							.startObject( OWNER_ID_TAG ).field("type", "string")
								.field("index", NOT_ANALYZED_INDEX).endObject()
							// OWNER_NAME_TAG
							.startObject( OWNER_NAME_TAG ).field("type", "string")
								.field("index", NOT_ANALYZED_INDEX).endObject()
							// COLLECTION_TIME
							.startObject( COLLECTION_TIME ).field("type", "date")
								.field("format", "strict_date_optional_time||epoch_millis").endObject() 
							// CUSTOM_GID_TAG
							.startObject( CUSTOM_GID_TAG ).field("type", "string")
								.field("index", NOT_ANALYZED_INDEX).endObject()
							// CUSTOM_UID_TAG
							.startObject( CUSTOM_UID_TAG ).field("type", "string")
								.field("index", NOT_ANALYZED_INDEX).endObject()	
						    // CUSTOM_MODIFIED_TIME_TAG
							.startObject( CUSTOM_MODIFIED_TIME_TAG ).field("type", "string")
								.field("index", NOT_ANALYZED_INDEX).endObject()	
							.endObject()
							
							// =================================
							// Dynamic fields won't be analyzed
							// =================================
							.startArray("dynamic_templates")
							  .startObject()
								.startObject("notanalyzed")
									.field("match", "*")
									.field("match_mapping_type", "string")
										.startObject( "mapping" ).field("type", "string")
											.field("index", NOT_ANALYZED_INDEX).endObject()
								.endObject()
							  .endObject()
							 .endArray()
								
						.endObject()
					.endObject()		)
				.execute().actionGet();

			if (putMappingResponse.isAcknowledged()) {
				LOGGER.info("Index Created: " + S3_OBJECT_INDEX_NAME);
				
			} else {
				LOGGER.error("Index {} did not exist. " + 
						"While attempting to create the index from stored ElasticSearch " +
						"Templates we were unable to get an acknowledgement.", S3_OBJECT_INDEX_NAME);
				LOGGER.error("Error Message: {}", putMappingResponse.toString());
				throw new RuntimeException("Unable to create index " + S3_OBJECT_INDEX_NAME);
			}			

		} catch (IOException e) {
			throw new RuntimeException( "Unable to create index " + 
					S3_OBJECT_INDEX_NAME +
					" " + e.getMessage()           );  
		}
	}
	

	/**
	 * Converts Object data into JSON format
	 * 
	 * @param s3Object
	 * @param namespace
	 * @param bucket
	 * @param collectionTime
	 * @param builder
	 * @return
	 */
	private static XContentBuilder toJsonFormat( S3Object s3Object, 
			String namespace, 
			String bucket,
			Date collectionTime,
			XContentBuilder builder) {

		try {
			if(builder == null) {
				builder = XContentFactory.jsonBuilder();
			}

			// add relevant fileds
			builder = builder.startObject()	    
					.field( LAST_MODIFIED_TAG, s3Object.getLastModified() )
					.field( SIZE_TAG, s3Object.getSize() )
					.field( KEY_TAG, s3Object.getKey() )
					.field( KEY_TAG + ANALYZED_TAG, s3Object.getKey() )
					.field( ETAG_TAG , s3Object.getETag())
					.field( NAMESPACE_TAG, namespace )
					.field( BUCKET_TAG, bucket )
					.field( OWNER_ID_TAG, (s3Object.getOwner() != null && s3Object.getOwner().getId() != null)
																			? s3Object.getOwner().getId() : null )	
					.field( OWNER_NAME_TAG, (s3Object.getOwner() != null && s3Object.getOwner().getDisplayName() != null) 
																			? s3Object.getOwner().getDisplayName() : null )	
					.field( COLLECTION_TIME, collectionTime )
					.endObject();

		} catch (IOException e) {
			throw new RuntimeException(e.getLocalizedMessage());
		}	

		return builder;
	}

	/**
	 * Init Object version index
	 */
	private void initS3ObjectVersionIndex() {

		if (elasticClient
				.admin()
				.indices()
				.exists(new IndicesExistsRequest(S3_OBJECT_VERSION_INDEX_NAME))
				.actionGet()
				.isExists()) {
			// Index already exists no need to re-create it
			return;
		}

		elasticClient.admin().indices().create(new CreateIndexRequest(S3_OBJECT_VERSION_INDEX_NAME)).actionGet();	

		try {
			PutMappingResponse putMappingResponse = elasticClient.admin().indices()
					.preparePutMapping(S3_OBJECT_VERSION_INDEX_NAME)
					.setType(S3_OBJECT_VERSION_INDEX_TYPE)
					.setSource(XContentFactory.jsonBuilder().prettyPrint()
					  .startObject()
						.startObject(S3_OBJECT_VERSION_INDEX_TYPE)
						
							// ========================================
							// Define how the basic fields are defined
							// ========================================
							.startObject("properties")
							// LAST_MODIFIED_TAG
							.startObject( LAST_MODIFIED_TAG ).field("type", "date")
								.field("format", "strict_date_optional_time||epoch_millis").endObject()
							// SIZE_TAG
							.startObject( SIZE_TAG ).field("type", "string").field("type", "long").endObject()
							// KEY_TAG
							.startObject( KEY_TAG ).field("type", "string")
								.field("index", NOT_ANALYZED_INDEX).endObject()
							// KEY_TAG Analyzed
							.startObject( KEY_TAG + ANALYZED_TAG).field("type", "string")
								.field("index", ANALYZED_INDEX).endObject()
							.startObject( ETAG_TAG ).field("type", "string")
								.field("index", NOT_ANALYZED_INDEX).endObject()	
							// NAMESPACE_TAG
							.startObject( NAMESPACE_TAG ).field("type", "string")
								.field("index", NOT_ANALYZED_INDEX).endObject()
							// BUCKET_TAG
							.startObject( BUCKET_TAG ).field("type", "string")
								.field("index", NOT_ANALYZED_INDEX).endObject()
							// VERSION_ID_TAG
							.startObject( VERSION_ID_TAG ).field("type", "string")
								.field("index", NOT_ANALYZED_INDEX).endObject()	
							// IS_LATEST_TAG
							.startObject( IS_LATEST_TAG ).field("type", "boolean")
								.field("index", NOT_ANALYZED_INDEX).endObject()		
							// OWNER_ID_TAG
							.startObject( OWNER_ID_TAG ).field("type", "string")
								.field("index", NOT_ANALYZED_INDEX).endObject()
							// OWNER_NAME_TAG
							.startObject( OWNER_NAME_TAG ).field("type", "string")
								.field("index", NOT_ANALYZED_INDEX).endObject()
							// COLLECTION_TIME
							.startObject( COLLECTION_TIME ).field("type", "date")
								.field("format", "strict_date_optional_time||epoch_millis").endObject() 
							.endObject()
							
							// =================================
							// Dynamic fields won't be analyzed
							// =================================
							.startArray("dynamic_templates")
							  .startObject()
								.startObject("notanalyzed")
									.field("match", "*")
									.field("match_mapping_type", "string")
										.startObject( "mapping" ).field("type", "string")
											.field("index", NOT_ANALYZED_INDEX).endObject()
								.endObject()
							  .endObject()
							 .endArray()
								
						.endObject()
					.endObject()		)
				.execute().actionGet();

			if (putMappingResponse.isAcknowledged()) {
				LOGGER.info("Index Created: " + S3_OBJECT_INDEX_NAME);
			} else {
				LOGGER.error("Index {} did not exist. " + 
						"While attempting to create the index from stored ElasticSearch " +
						"Templates we were unable to get an acknowledgement.", S3_OBJECT_INDEX_NAME);
				LOGGER.error("Error Message: {}", putMappingResponse.toString());
				throw new RuntimeException("Unable to create index " + S3_OBJECT_INDEX_NAME);
			}			

		} catch (IOException e) {
			throw new RuntimeException( "Unable to create index " + 
					S3_OBJECT_INDEX_NAME +
					" " + e.getMessage()           );  
		}
	}
	
	/**
	 * Converts object version data to json 
	 * 
	 * @param version
	 * @param namespace
	 * @param bucket
	 * @param collectionTime
	 * @param builder
	 * @return
	 */
	private static XContentBuilder toJsonFormat( Version version, 
												 String   namespace, 
												 String   bucket,
												 Date     collectionTime,
												 XContentBuilder builder) {

		try {
			if(builder == null) {
				builder = XContentFactory.jsonBuilder();
			}

			// add relevant fields
			builder = builder.startObject()	    
					.field( LAST_MODIFIED_TAG, version.getLastModified() )
					.field( SIZE_TAG, version.getSize() )
					.field( KEY_TAG, version.getKey() )
					.field( KEY_TAG + ANALYZED_TAG, version.getKey() )
					.field( ETAG_TAG , version.getETag())
					.field( NAMESPACE_TAG, namespace )
					.field( BUCKET_TAG, bucket )
					.field( VERSION_ID_TAG, version.getVersionId() )
					.field( IS_LATEST_TAG, version.isLatest())
					.field( OWNER_ID_TAG, (version.getOwner() != null && version.getOwner().getId() != null)
																			? version.getOwner().getId() : null )	
					.field( OWNER_NAME_TAG, (version.getOwner() != null && version.getOwner().getDisplayName() != null) 
																			? version.getOwner().getDisplayName() : null )	
					.field( COLLECTION_TIME, collectionTime )
					.endObject();

		} catch (IOException e) {
			throw new RuntimeException(e.getLocalizedMessage());
		}	

		return builder;
	}
	
	/**
	 * Converts
	 * 
	 * @param deleteMarker
	 * @param namespace
	 * @param bucket
	 * @param collectionTime
	 * @param builder
	 * @return
	 */
	private static XContentBuilder toJsonFormat( DeleteMarker deleteMarker, 
			String   namespace, 
			String   bucket,
			Date     collectionTime,
			XContentBuilder builder) {

		try {
			if(builder == null) {
				builder = XContentFactory.jsonBuilder();
			}

			// add relevant fields
			builder = builder.startObject()	    
					.field( LAST_MODIFIED_TAG, deleteMarker.getLastModified() )
					.field( KEY_TAG, deleteMarker.getKey() )
					.field( KEY_TAG + ANALYZED_TAG, deleteMarker.getKey() )	
					.field( NAMESPACE_TAG, namespace )
					.field( BUCKET_TAG, bucket )
					.field( VERSION_ID_TAG, deleteMarker.getVersionId() )
					.field( IS_LATEST_TAG, deleteMarker.isLatest())
					.field( OWNER_ID_TAG, (deleteMarker.getOwner() != null && deleteMarker.getOwner().getId() != null)
							                ? deleteMarker.getOwner().getId() : null )	
					.field( OWNER_NAME_TAG, (deleteMarker.getOwner() != null && deleteMarker.getOwner().getDisplayName() != null) 
									          ? deleteMarker.getOwner().getDisplayName() : null )	
					.field( COLLECTION_TIME, collectionTime )
					.endObject();

		} catch (IOException e) {
			throw new RuntimeException(e.getLocalizedMessage());
		}	

		return builder;
	}
	
	/**
	 * Converts Query Object data into JSON
	 * 
	 * @param queryObject
	 * @param namespace
	 * @param bucket
	 * @param collectionTime
	 * @param builder
	 * @return
	 */
	private static XContentBuilder toJsonFormat( QueryObject queryObject, 
			String namespace, 
			String bucket,
			Date collectionTime,
			XContentBuilder builder) {

		try {
			if(builder == null) {
				builder = XContentFactory.jsonBuilder();
			}
						
			// add known basic fields
			builder = builder.startObject()	    
					.field( KEY_TAG, queryObject.getObjectName() )
					.field( KEY_TAG + ANALYZED_TAG, queryObject.getObjectName() )
					.field( ETAG_TAG , queryObject.getObjectId())
					.field( NAMESPACE_TAG, namespace )
					.field( BUCKET_TAG, bucket )
					.field( COLLECTION_TIME, collectionTime );
			
			// Add custom MS Key values as dynamic fields
			for( QueryMetadata metadata : queryObject.getQueryMds() ) {
				for( Entry<String, String> entry : metadata.getMdMap().entrySet() ) {
					builder.field(entry.getKey(), entry.getValue());
				}
			}
			
			builder.endObject();

		} catch (IOException e) {
			throw new RuntimeException(e.getLocalizedMessage());
		}	

		return builder;
	}


	/**
	 * Purges documents based on collection dates
	 * 
	 * @param thresholdDate
	 * @param indexName
	 * @param indexType
	 * @return
	 */
	private Long purgeIndex(Date thresholdDate, String indexName, String indexType) {
		
		Long deletedDocs = 0L;
		
		String thresholdDateString = OLD_DATA_DATE_FORMAT.format(thresholdDate);
		QueryBuilder qb = QueryBuilders.rangeQuery(COLLECTION_TIME).lt(thresholdDateString);
		BoolQueryBuilder boolQuery = QueryBuilders.boolQuery().filter(qb);
		
		// Purge 
		SearchRequestBuilder searchRequestBuilder = elasticClient.prepareSearch(indexName);
		searchRequestBuilder.setTypes(indexType);
		searchRequestBuilder.setQuery(boolQuery);
		// just need the id
		searchRequestBuilder.setNoFields();
		// limit the query to 25000 entries
		searchRequestBuilder.setSize(25000);
		// limit search time to 15 seconds
		searchRequestBuilder.setScroll(new TimeValue(15000));
		
		SearchResponse searchResponse;
		
		do {
			searchResponse = searchRequestBuilder.execute().actionGet();
			SearchHits searchHits = searchResponse.getHits();
			Iterator<SearchHit> itr = searchHits.iterator();	

			BulkRequestBuilder requestBuilder = elasticClient.prepareBulk();

			// loop all found entries and 
			// add them to bulk delete request
			while( itr.hasNext() ) { 
				SearchHit searchHit = itr.next();	

				DeleteRequest deleteRequest = new DeleteRequest()
				.index(indexName)
				.type(indexType)
				.id(searchHit.getId());

				requestBuilder.add(deleteRequest);
			}

			if (requestBuilder.numberOfActions() > 0 ) {
				LOGGER.info("Found " + requestBuilder.numberOfActions() + " documents to delete in Elasticsearch index: " + 
				        indexName + " due to collection_time < " + thresholdDateString);
			} else {
				// nothing was found no need 
				// to continue further
				return deletedDocs;
			}

			BulkResponse bulkResponse = requestBuilder.execute().actionGet();
			int items = bulkResponse.getItems().length;

			LOGGER.info( "Took " + bulkResponse.getTookInMillis() + " ms to delete [" + items + "] items in Elasticsearch " + "index: " + 
					     indexName + " index type: " +  indexType ); 

			deletedDocs += items;
			
			if( bulkResponse.hasFailures() ) {
				LOGGER.error( "Failure(s) occured while items in Elasticsearch" + "index: " + 
						      indexName + " index type: " +  indexType );

			}
		} while ( (searchResponse != null) && 
				(searchResponse.getHits() != null) && 
				(searchResponse.getHits().getTotalHits() > 0) );
		
		return deletedDocs;
	}




}
