package com.emc.ecs.metadata.dao.elasticsearch;



import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Date;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.elasticsearch.action.admin.indices.create.CreateIndexRequest;
import org.elasticsearch.action.admin.indices.exists.indices.IndicesExistsRequest;
import org.elasticsearch.action.admin.indices.mapping.put.PutMappingResponse;
import org.elasticsearch.client.transport.TransportClient;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.settings.Settings.Builder;
import org.elasticsearch.common.transport.InetSocketTransportAddress;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentFactory;

import com.emc.ecs.management.entity.BucketBillingInfo;
import com.emc.ecs.management.entity.NamespaceBillingInfo;
import com.emc.ecs.management.entity.ObjectBucket;
import com.emc.ecs.management.entity.ObjectBuckets;
import com.emc.ecs.metadata.dao.BillingDAO;
import com.emc.ecs.metadata.dao.elasticsearch.entity.ElasticNamespaceBillingInfo;
import com.emc.ecs.metadata.dao.elasticsearch.entity.ElasticObjectBucket;

public class ElasticBillingDAO implements BillingDAO {

	private final static String CLIENT_SNIFFING_CONFIG       = "client.transport.sniff";
	private final static String CLIENT_CLUSTER_NAME_CONFIG   = "cluster.name";
	public  final static String BILLING_NAMESPACE_INDEX_NAME = "ecs-billing-namespace";
	public  final static String BILLING_BUCKET_INDEX_NAME    = "ecs-billing-bucket";
	public  final static String NAMESPACE_BILLING_INDEX_TYPE = "namespace-info";
	public  final static String BUCKET_BILLING_INDEX_TYPE    = "bucket-info";
	public  final static String BUCKET_INDEX_NAME            = "ecs-bucket";
	public  final static String OBJECT_BUCKET_INDEX_TYPE     = "object-bucket";
	public  final static String COLLECTION_TIME              = "collection_time";
	
	//=======================
	// Private members
	//=======================
	private TransportClient elasticClient;
	private static Logger LOGGER = LoggerFactory.getLogger(ElasticBillingDAO.class);
	
	//========================
	// Constructor
	//========================
	public ElasticBillingDAO(ElasticDAOConfig config) {
		
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
				elasticClient.addTransportAddress(
						new InetSocketTransportAddress(InetAddress.getByName(elasticHost), config.getPort()));				
			}
			
			// init indexes
			
			initBillingNamespaceIndex();
			
			initBillingBucketIndex();
			
			initObjectBucketIndex();
			
		} catch (UnknownHostException e) {
			throw new RuntimeException("Unable to initialize Eleasticsearch client " + e.getLocalizedMessage() );
		}				
	}

	//========================
	// Public methods
	//========================
	/**
	 * Inserts namespace billing info into Elasticsearch
	 * @param NamespaceBillingInfo
	 * @param Date
	 */
	@Override
	public void insert( NamespaceBillingInfo billingData, Date collectionTime ) {
				
		// Generate JSON for namespace billing info
		XContentBuilder namespaceBuilder = toJsonFormat(billingData, collectionTime);				
		elasticClient.prepareIndex(BILLING_NAMESPACE_INDEX_NAME, NAMESPACE_BILLING_INDEX_TYPE).setSource(namespaceBuilder).get();
		
		
		// Generate JSON for namespace billing info
		for(BucketBillingInfo bucketBillingInfo : billingData.getBucketBillingInfo()) {
			XContentBuilder bucketBuilder = toJsonFormat(bucketBillingInfo, collectionTime);			
			elasticClient.prepareIndex(BILLING_BUCKET_INDEX_NAME, BUCKET_BILLING_INDEX_TYPE).setSource(bucketBuilder).get();			
		}
	}

	/**
	 * Inserts Bucket related information into Elasticsearch
	 * @param ObjectBuckets
	 * @param Date
	 */
	@Override
	public void insert( ObjectBuckets objectBuckets, Date collectionTime ) {
		
		if( objectBuckets == null || objectBuckets.getObjectBucket() == null ) {
			return;
		}
		
		
		// Generate JSON for object buckets info
		for( ObjectBucket objectBucket : objectBuckets.getObjectBucket() ) {
			XContentBuilder objectBucketBuilder = toJsonFormat(objectBucket, collectionTime);
			elasticClient.prepareIndex(BUCKET_INDEX_NAME, OBJECT_BUCKET_INDEX_TYPE).setSource(objectBucketBuilder).get();
		}		
	}
	
	//=======================
	// Private methods
	//=======================
	
	//===========================
	// Billing namespace methods
	//===========================
	
	private void initBillingNamespaceIndex() {
		
		if (elasticClient
				.admin()
				.indices()
				.exists(new IndicesExistsRequest(BILLING_NAMESPACE_INDEX_NAME))
				.actionGet()
				.isExists()) {
			// Index already exists no need to re-create it
			return;
		}

		elasticClient.admin().indices().create(new CreateIndexRequest(BILLING_NAMESPACE_INDEX_NAME)).actionGet();	
		
		try {
			PutMappingResponse putMappingResponse = elasticClient.admin().indices()
			    .preparePutMapping(BILLING_NAMESPACE_INDEX_NAME)
			    .setType(NAMESPACE_BILLING_INDEX_TYPE)
			    .setSource(XContentFactory.jsonBuilder().prettyPrint()
			                .startObject()
			                    .startObject(NAMESPACE_BILLING_INDEX_TYPE)
			                        .startObject("properties")
			                            .startObject( NamespaceBillingInfo.TOTAL_SIZE_TAG ).field("type", "long").endObject()
			                            .startObject( NamespaceBillingInfo.TOTAL_SIZE_UNIT_TAG ).field("type", "string")
			                            	.field("index", "not_analyzed").endObject()   
			                            .startObject( NamespaceBillingInfo.TOTAL_OBJECTS_TAG ).field("type", "long").endObject()
			                            .startObject( NamespaceBillingInfo.NAMESPACE_TAG ).field("type", "string").
			                            	field("index", "not_analyzed").endObject()
			                            .startObject( ElasticNamespaceBillingInfo.COLLECTION_TIME ).field("type", "date")
			                            	.field("format", "strict_date_optional_time||epoch_millis").endObject() 
			                        .endObject()
			                    .endObject()
			                .endObject())
			    .execute().actionGet();
			
			if (putMappingResponse.isAcknowledged()) {
	            LOGGER.info("Index Created: {}", BILLING_NAMESPACE_INDEX_NAME);
	        } else {
	            LOGGER.error("Index {} did not exist. " + 
	                         "While attempting to create the index from stored ElasticSearch " +
	            		     "Templates we were unable to get an acknowledgement.", BILLING_NAMESPACE_INDEX_NAME);
	            LOGGER.error("Error Message: {}", putMappingResponse.toString());
	            throw new RuntimeException("Unable to create index " + BILLING_NAMESPACE_INDEX_NAME);
	        }			
			
		} catch (IOException e) {
			 throw new RuntimeException( "Unable to create index " + 
					 					 BILLING_NAMESPACE_INDEX_NAME +
					 					 " " + e.getMessage()           );  
		}
		
	}
	
	private static XContentBuilder toJsonFormat( NamespaceBillingInfo billingInfo, 
			Date collectionTime, 
			XContentBuilder builder         ) {

		try {
			if(builder == null) {
				builder = XContentFactory.jsonBuilder();
			}		

			// namespace portion
			builder = builder.startObject()
					.field(NamespaceBillingInfo.TOTAL_SIZE_TAG, billingInfo.getTotalSize())
					.field(NamespaceBillingInfo.TOTAL_SIZE_UNIT_TAG, billingInfo.getTotalSizeUnit())     
					.field(NamespaceBillingInfo.TOTAL_OBJECTS_TAG, billingInfo.getTotalObjects())
					.field(NamespaceBillingInfo.NAMESPACE_TAG, billingInfo.getNamespace())	
					.field(COLLECTION_TIME, collectionTime)
					.endObject();


		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}	

		return builder;
	}


	public static XContentBuilder toJsonFormat( NamespaceBillingInfo billingInfo, Date collectionTime ) {						
		return toJsonFormat(billingInfo, collectionTime, null);
	}
	

	//=======================
	// Billing bucket methods
	//=======================
	
	private void initBillingBucketIndex() {
		
		if (elasticClient
				.admin()
				.indices()
				.exists(new IndicesExistsRequest(BILLING_BUCKET_INDEX_NAME))
				.actionGet()
				.isExists()) {
			// Index already exists no need to re-create it
			return;
		}

		elasticClient.admin().indices().create(new CreateIndexRequest(BILLING_BUCKET_INDEX_NAME)).actionGet();	
		
		
		
		try {
			PutMappingResponse putMappingResponse = elasticClient.admin().indices()
			    .preparePutMapping(BILLING_BUCKET_INDEX_NAME)
			    .setType(BUCKET_BILLING_INDEX_TYPE)
			    .setSource(XContentFactory.jsonBuilder().prettyPrint()
			                .startObject()
			                    .startObject(BUCKET_BILLING_INDEX_TYPE)
			                        .startObject("properties")
			                            .startObject( BucketBillingInfo.NAME_TAG ).field("type", "string")
			                            	.field("index", "not_analyzed").endObject()
			                            .startObject( BucketBillingInfo.NAMESPACE_TAG ).field("type", "string")
			                            	.field("index", "not_analyzed").endObject()   
			                            .startObject( BucketBillingInfo.TOTAL_OBJECTS_TAG ).field("type", "long").endObject()
			                            .startObject( BucketBillingInfo.TOTAL_SIZE_TAG ).field("type", "long").endObject()
			                            .startObject( BucketBillingInfo.TOTAL_SIZE_UNIT_TAG ).field("type", "string")
		                            		.field("index", "not_analyzed").endObject() 
		                            	.startObject( BucketBillingInfo.VPOOL_ID_TAG ).field("type", "string")
	                            			.field("index", "not_analyzed").endObject() 
			                            .startObject( COLLECTION_TIME ).field("type", "date")
			                            	.field("format", "strict_date_optional_time||epoch_millis").endObject() 
			                        .endObject()
			                    .endObject()
			                .endObject())
			    .execute().actionGet();
			
			if (putMappingResponse.isAcknowledged()) {
	            LOGGER.info("Index Created: {}", BILLING_BUCKET_INDEX_NAME);
	        } else {
	            LOGGER.error("Index {} did not exist. " + 
	                         "While attempting to create the index from stored ElasticSearch " +
	            		     "Templates we were unable to get an acknowledgement.", BILLING_BUCKET_INDEX_NAME);
	            LOGGER.error("Error Message: {}", putMappingResponse.toString());
	            throw new RuntimeException("Unable to create index " + BILLING_BUCKET_INDEX_NAME);
	        }			
			
		} catch (IOException e) {
			 throw new RuntimeException( "Unable to create index " + 
					 					 BILLING_BUCKET_INDEX_NAME +
					 					 " " + e.getMessage()           );  
		}
		
	}
	
	
	private static XContentBuilder toJsonFormat(BucketBillingInfo bucketInfo, 
			Date collectionTime,
			XContentBuilder builder) {

		try {
			if(builder == null) {
				builder = XContentFactory.jsonBuilder();
			}

			// initial portion
			builder = builder.startObject()	    
					.field(BucketBillingInfo.NAME_TAG, bucketInfo.getName())
					.field(BucketBillingInfo.NAMESPACE_TAG, bucketInfo.getNamespace())
					.field(BucketBillingInfo.TOTAL_OBJECTS_TAG, bucketInfo.getTotalObjects())			
					.field(BucketBillingInfo.TOTAL_SIZE_TAG, bucketInfo.getTotalSize())
					.field(BucketBillingInfo.TOTAL_SIZE_UNIT_TAG, bucketInfo.getTotalSizeUnit())
					.field(BucketBillingInfo.VPOOL_ID_TAG, bucketInfo.getVpoolId())
					.field(COLLECTION_TIME, collectionTime)
					.endObject();

		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}	

		return builder;
	}


	private static XContentBuilder toJsonFormat( BucketBillingInfo bucketInfo, Date collectionTime ) {						
		return toJsonFormat(bucketInfo, collectionTime, null);
	}
	

	//======================
	// Object bucket methods
	//======================
	
	private void initObjectBucketIndex() {
		
		if (elasticClient
				.admin()
				.indices()
				.exists(new IndicesExistsRequest(BUCKET_INDEX_NAME))
				.actionGet()
				.isExists()) {
			// Index already exists no need to re-create it
			return;
		}

		elasticClient.admin().indices().create(new CreateIndexRequest(BUCKET_INDEX_NAME)).actionGet();	
		
		
		
		try {
			PutMappingResponse putMappingResponse = elasticClient.admin().indices()
					.preparePutMapping(BUCKET_INDEX_NAME)
					.setType(OBJECT_BUCKET_INDEX_TYPE)
					.setSource(XContentFactory.jsonBuilder().prettyPrint()
							.startObject()
							.startObject(OBJECT_BUCKET_INDEX_TYPE)
								.startObject("properties")
								.startObject(ObjectBucket.CREATED_TAG).field("type", "string")
										.field("index", "not_analyzed").endObject()            				     
									.startObject(ObjectBucket.SOFT_QUOTA_TAG).field("type", "string")
										.field("index", "not_analyzed").endObject()  
									.startObject(ObjectBucket.FS_ACCESS_ENABLED_TAG).field("type", "boolean").endObject()
									.startObject(ObjectBucket.LOCKED_TAG).field("type", "boolean").endObject()
									.startObject(ObjectBucket.V_POOL_TAG).field("type", "string")
										.field("index", "not_analyzed").endObject()
									.startObject(ObjectBucket.NAMESPACE_TAG).field("type", "string")
										.field("index", "not_analyzed").endObject()
									.startObject(ObjectBucket.OWNER_TAG).field("type", "string")
										.field("index", "not_analyzed").endObject()
									.startObject(ObjectBucket.IS_STALE_ALLOWED_TAG).field("type", "boolean").endObject()
									.startObject(ObjectBucket.IS_ENCRYPTION_ENABLED_TAG).field("type", "boolean").endObject()
									.startObject(ObjectBucket.DEFAULT_RETENTION_TAG).field("type", "long").endObject()
									.startObject(ObjectBucket.BLOCK_SIZE_TAG).field("type", "long").endObject()
									.startObject(ObjectBucket.NOTIFICATION_SIZE_TAG).field("type", "long").endObject()
									.startObject(ObjectBucket.API_TYPE_TAG).field("type", "string")
										.field("index", "not_analyzed").endObject()
									//.startObject(ObjectBucket.TAG_SET_TAG)
									.startObject(ObjectBucket.RETENTION_TAG).field("type", "long").endObject()
									.startObject(ObjectBucket.DEFAULT_GROUP_FILE_READ_PERMISSION_TAG).field("type", "boolean").endObject()
									.startObject(ObjectBucket.DEFAULT_GROUP_FILE_WRITE_PERMISSION_TAG).field("type", "boolean").endObject()
									.startObject(ObjectBucket.DEFAULT_GROUP_FILE_EXECUTE_PERMISSION_TAG).field("type", "boolean").endObject()
									.startObject(ObjectBucket.DEFAULT_GROUP_DIR_READ_PERMISSION_TAG).field("type", "boolean").endObject()
									.startObject(ObjectBucket.DEFAULT_GROUP_DIR_WRITE_PERMISSION_TAG).field("type", "boolean").endObject()
									.startObject(ObjectBucket.DEFAULT_GROUP_DIR_EXECUTE_PERMISSION_TAG).field("type", "boolean").endObject()
									.startObject(ObjectBucket.DEFAULT_GROUP_TAG).field("type", "string")
										.field("index", "not_analyzed").endObject()				
									.startObject(ObjectBucket.NAME_TAG).field("type", "string")
										.field("index", "not_analyzed").endObject()
									.startObject(ObjectBucket.ID_TAG).field("type", "string")
										.field("index", "not_analyzed").endObject()
									.startObject(ObjectBucket.LINK_TAG).field("type", "string")
										.field("index", "not_analyzed").endObject()		     
									.startObject(ObjectBucket.CREATION_TIME_TAG).field("type", "date")
										.field("format", "strict_date_optional_time||epoch_millis").endObject()			     
									.startObject(ObjectBucket.INACTIVE_TAG).field("type", "boolean").endObject()
									.startObject(ObjectBucket.GLOBAL_TAG).field("type", "boolean").endObject()
									.startObject(ObjectBucket.REMOTE_TAG).field("type", "boolean").endObject()
									.startObject(ObjectBucket.VDC_TAG).field("type", "string")
										.field("index", "not_analyzed").endObject()
									.startObject(ObjectBucket.INTERNAL_TAG).field("type", "boolean").endObject()							
									.startObject(COLLECTION_TIME).field("type", "date")
										.field("format", "strict_date_optional_time||epoch_millis").endObject()
								.endObject()
							.endObject()
						.endObject())
					.execute().actionGet();

			if (putMappingResponse.isAcknowledged()) {
	            LOGGER.info("Index Created: {}", BUCKET_INDEX_NAME);
	        } else {
	            LOGGER.error("Index {} did not exist. " + 
	                         "While attempting to create the index from stored ElasticSearch " +
	            		     "Templates we were unable to get an acknowledgement.", BUCKET_INDEX_NAME);
	            LOGGER.error("Error Message: {}", putMappingResponse.toString());
	            throw new RuntimeException("Unable to create index " + BUCKET_INDEX_NAME);
	        }			
			
		} catch (IOException e) {
			 throw new RuntimeException( "Unable to create index " + 
					 					 BILLING_BUCKET_INDEX_NAME +
					 					 " " + e.getMessage()           );  
		}
		
	}
	
	
	private static XContentBuilder toJsonFormat( ObjectBucket objectBucket, 
			Date collectionTime,
			XContentBuilder builder) {

		try {
			if(builder == null) {
				builder = XContentFactory.jsonBuilder();
			}

			// initial portion
			builder = builder.startObject()	
					.field(ObjectBucket.CREATED_TAG, objectBucket.getCreated())            				     
					.field(ObjectBucket.SOFT_QUOTA_TAG, objectBucket.getSoftQuota())
					.field(ObjectBucket.FS_ACCESS_ENABLED_TAG, objectBucket.getFsAccessEnabled())
					.field(ObjectBucket.LOCKED_TAG, objectBucket.getLocked())
					.field(ObjectBucket.V_POOL_TAG, objectBucket.getVpool())
					.field(ObjectBucket.NAMESPACE_TAG, objectBucket.getNamespace())
					.field(ObjectBucket.OWNER_TAG, objectBucket.getOwner())
					.field(ObjectBucket.IS_STALE_ALLOWED_TAG, objectBucket.getIsStaleAllowed())
					.field(ObjectBucket.IS_ENCRYPTION_ENABLED_TAG, objectBucket.getIsEncryptionEnabled())
					.field(ObjectBucket.DEFAULT_RETENTION_TAG, objectBucket.getDefaultRetention())
					.field(ObjectBucket.BLOCK_SIZE_TAG, objectBucket.getBlockSize())
					.field(ObjectBucket.NOTIFICATION_SIZE_TAG, objectBucket.getNotificationSize())
					.field(ObjectBucket.API_TYPE_TAG, objectBucket.getApiType())
					//.field(ObjectBucket.TAG_SET_TAG, objectBucket.getTagSet())
					.field(ObjectBucket.RETENTION_TAG, objectBucket.getRetention())
					.field(ObjectBucket.DEFAULT_GROUP_FILE_READ_PERMISSION_TAG, objectBucket.getDefaultGroupFileReadPermission())
					.field(ObjectBucket.DEFAULT_GROUP_FILE_WRITE_PERMISSION_TAG, objectBucket.getDefaultGroupFileWritePermission())
					.field(ObjectBucket.DEFAULT_GROUP_FILE_EXECUTE_PERMISSION_TAG, objectBucket.getDefaultGroupFileExecutePermission())
					.field(ObjectBucket.DEFAULT_GROUP_DIR_READ_PERMISSION_TAG, objectBucket.getDefaultGroupDirReadPermission())
					.field(ObjectBucket.DEFAULT_GROUP_DIR_WRITE_PERMISSION_TAG, objectBucket.getDefaultGroupDirWritePermission())
					.field(ObjectBucket.DEFAULT_GROUP_DIR_EXECUTE_PERMISSION_TAG, objectBucket.getDefaultGroupDirExecutePermission())
					.field(ObjectBucket.DEFAULT_GROUP_TAG, objectBucket.getDefaultGroup())
					// To decide if search tag should be included
					//.field(ObjectBucket.SEARCH_METADATA_TAG, objectBucket.getSearchMetadata())					
					.field(ObjectBucket.NAME_TAG, objectBucket.getName())
					.field(ObjectBucket.ID_TAG, (objectBucket.getId() != null) ? objectBucket.getId().toString() : null)
					.field(ObjectBucket.LINK_TAG, objectBucket.getLink())			     
					.field(ObjectBucket.CREATION_TIME_TAG, objectBucket.getCreationTime())			     
					.field(ObjectBucket.INACTIVE_TAG, objectBucket.getInactive())
					.field(ObjectBucket.GLOBAL_TAG, objectBucket.getGlobal())
					.field(ObjectBucket.REMOTE_TAG, objectBucket.getRemote())
					.field(ObjectBucket.VDC_TAG, (objectBucket.getVdc() != null) ? objectBucket.getVdc().toString() : null)
					.field(ObjectBucket.INTERNAL_TAG, objectBucket.getInternal())								
					.field(COLLECTION_TIME, collectionTime)
					.endObject();

		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}	

		return builder;
	}


	public static XContentBuilder toJsonFormat( ObjectBucket objectBucket, Date collectionTime ) {						
		return toJsonFormat(objectBucket, collectionTime, null);
	}
	

}
