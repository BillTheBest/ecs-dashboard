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



package com.emc.ecs.metadata.dao;

import java.util.Date;

import com.emc.object.s3.bean.ListObjectsResult;
import com.emc.object.s3.bean.ListVersionsResult;
import com.emc.object.s3.bean.QueryObjectsResult;

public interface ObjectDAO {

	public enum ObjectDataType {
		object,
		object_versions
	};
	
	/**
	 * Inserts list object data into datastore
	 * @param listObjectsResult
	 * @param namespace
	 * @param bucketName
	 * @param collectionTime
	 */
	public void insert( ListObjectsResult listObjectsResult, String namespace,
						String bucketName, Date collectionTime );
	
	/**
	 * Inserts query object data into datastore
	 * @param listObjectsResult
	 * @param namespace
	 * @param bucketName
	 * @param collectionTime
	 */
	public void insert( QueryObjectsResult queryObjectsResult, String namespace,
						String bucketName, Date collectionTime );

	/**
	 * Inserts object versions data into datastore
	 * @param listVersionResult
	 * @param namespace
	 * @param name
	 * @param collectionTime
	 */
	public void insert( ListVersionsResult listVersionsResult, String namespace,
						String name, Date collectionTime);
	
	
	/**
	 * Purges object data collected before a certain date
	 * 
	 * @param type
	 * @param collectionTime
	 * @return Long
	 */
	public Long purgeOldData( ObjectDataType type, Date collectionTime);
	
	
}
