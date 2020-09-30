/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.runtime.io.network.partition;

import org.apache.flink.core.memory.MemorySegment;
import org.apache.flink.runtime.io.network.buffer.Buffer;
import org.apache.flink.runtime.io.network.buffer.Buffer.DataType;
import org.apache.flink.runtime.io.network.buffer.BufferRecycler;
import org.apache.flink.runtime.io.network.buffer.NetworkBuffer;
import org.apache.flink.runtime.io.network.netty.NettyMessage;
import org.apache.flink.runtime.io.network.partition.consumer.InputChannelID;

import javax.annotation.Nullable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

import static org.apache.flink.util.Preconditions.checkNotNull;

/**
 * Partition data wraps the required infos for both pipelined and bounded blocking partitions.
 */
public abstract class PartitionData {

	private final DataType nextDataType;
	protected final int backlog;
	protected final int sequenceNumber;

	PartitionData(int backlog, DataType nextDataType, int sequenceNumber) {
		this.backlog = backlog;
		this.nextDataType = checkNotNull(nextDataType);
		this.sequenceNumber = sequenceNumber;
	}

	public abstract boolean isBuffer();

	/**
	 * Returns the buffer-format partition data with the provided memory segment or not.
	 *
	 * @param segment it might be needed to read the partition data into.
	 * @return the buffer represents the partition data.
	 */
	public abstract Buffer getBuffer(@Nullable MemorySegment segment) throws IOException;

	/**
	 * Builds the respective netty message {@link org.apache.flink.runtime.io.network.netty.NettyMessage.BufferResponse}
	 * or {@link org.apache.flink.runtime.io.network.netty.NettyMessage.FileRegionResponse} to be transported in network stack.
	 */
	public abstract NettyMessage buildMessage(InputChannelID receiverId) throws IOException;

	public DataType getNextDataType() {
		return nextDataType;
	}

	public int getSequenceNumber() {
		return sequenceNumber;
	}

	/**
	 * The pipelined partition or mmap-based bounded blocking partition provide the
	 * buffer-format data to be consumed.
	 */
	public static final class PartitionBuffer extends PartitionData {

		private final Buffer buffer;

		public PartitionBuffer(Buffer buffer, int backlog, DataType nextDataType, int sequenceNumber) {
			super(backlog, nextDataType, sequenceNumber);
			this.buffer = checkNotNull(buffer);
		}

		@Override
		public NettyMessage buildMessage(InputChannelID receiverId) {
			return new NettyMessage.BufferResponse(
				buffer,
				new NettyMessage.ResponseInfo(
					receiverId,
					sequenceNumber,
					backlog,
					buffer.getDataType(),
					buffer.isCompressed(),
					buffer.readableBytes()));
		}

		@Override
		public boolean isBuffer() {
			return buffer.isBuffer();
		}

		@Override
		public Buffer getBuffer(MemorySegment segment) {
			return buffer;
		}
	}

	/**
	 * The file-based bounded blocking partition provides the FileRegion-format data to be consumed.
	 */
	public static final class PartitionFileRegion extends PartitionData {

		private final FileChannel fileChannel;
		private final int dataSize;
		private final DataType dataType;
		private final boolean isCompressed;

		public PartitionFileRegion(
				FileChannel fileChannel,
				int dataSize,
				DataType dataType,
				boolean isCompressed,
				DataType nextDataType,
				int backlog,
				int sequenceNumber) {

			super(backlog, checkNotNull(nextDataType), sequenceNumber);
			this.fileChannel = checkNotNull(fileChannel);
			this.dataSize = dataSize;
			this.dataType = checkNotNull(dataType);
			this.isCompressed = isCompressed;
		}

		@Override
		public NettyMessage buildMessage(InputChannelID receiverId) throws IOException {
			return new NettyMessage.FileRegionResponse(
				fileChannel,
				new NettyMessage.ResponseInfo(
					receiverId,
					sequenceNumber,
					backlog,
					dataType,
					isCompressed,
					dataSize));
		}

		@Override
		public boolean isBuffer() {
			return dataType == DataType.DATA_BUFFER;
		}

		@Override
		public Buffer getBuffer(MemorySegment segment) throws IOException {
			final ByteBuffer buffer = segment.wrap(0, dataSize);
			BufferReaderWriterUtil.readByteBufferFully(fileChannel, buffer);

			return new NetworkBuffer(
				segment,
				BufferRecycler.DummyBufferRecycler.INSTANCE,
				dataType,
				isCompressed,
				dataSize);
		}
	}
}
