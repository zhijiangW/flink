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

import org.apache.flink.runtime.io.network.buffer.Buffer;
import org.apache.flink.runtime.io.network.netty.NettyMessage;
import org.apache.flink.runtime.io.network.partition.ResultSubpartition.BufferAndBacklog;
import org.apache.flink.runtime.io.network.partition.consumer.InputChannelID;

import javax.annotation.Nullable;

import java.io.IOException;
import java.nio.channels.FileChannel;

/**
 * A view to consume a {@link ResultSubpartition} instance.
 */
public interface ResultSubpartitionView {

	/**
	 * Returns the next {@link Buffer} instance of this queue iterator.
	 *
	 * <p>If there is currently no instance available, it will return <code>null</code>.
	 * This might happen for example when a pipelined queue producer is slower
	 * than the consumer or a spilled queue needs to read in more data.
	 *
	 * <p><strong>Important</strong>: The consumer has to make sure that each
	 * buffer instance will eventually be recycled with {@link Buffer#recycleBuffer()}
	 * after it has been consumed.
	 */
	@Nullable
	RawMessage getNextRawMessage() throws IOException;

	void notifyDataAvailable();

	void releaseAllResources() throws IOException;

	boolean isReleased();

	void resumeConsumption();

	Throwable getFailureCause();

	boolean isAvailable(int numCreditsAvailable);

	int unsynchronizedGetNumberOfQueuedBuffers();

	int getDataBufferBacklog();

	abstract class RawMessage {
		private final boolean isDataAvailable;
		private final boolean isEventAvailable;
		protected final int buffersInBacklog;

		RawMessage(boolean isDataAvailable, boolean isEventAvailable, int buffersInBacklog) {
			this.isDataAvailable = isDataAvailable;
			this.isEventAvailable = isEventAvailable;
			this.buffersInBacklog = buffersInBacklog;
		}

		public boolean isMoreAvailable(int credits) {
			boolean moreAvailable;
			if (credits > 0) {
				moreAvailable = isDataAvailable;
			} else {
				moreAvailable = isEventAvailable;
			}
			return moreAvailable;
		}

		public boolean isBuffer() {
			return true;
		}

		public abstract NettyMessage buildMessage(InputChannelID id, int sequenceNumber) throws IOException;
	}

	class BufferRawMessage extends RawMessage {
		private final Buffer buffer;

		BufferRawMessage(Buffer buffer, boolean isDataAvailable, boolean isEventAvailable, int buffersInBacklog) {
			super(isDataAvailable, isEventAvailable, buffersInBacklog);
			this.buffer = buffer;
		}

		@Override
		public NettyMessage buildMessage(InputChannelID id, int sequenceNumber) {
			return new NettyMessage.BufferResponse(
				buffer,
				buffer.getDataType(),
				buffer.isCompressed(),
				sequenceNumber,
				id,
				buffersInBacklog,
				buffer.readableBytes());

		}

		@Override
		public boolean isBuffer() {
			return buffer.isBuffer();
		}
	}

	class FileRawMessage extends RawMessage {
		private final FileChannel fileChannel;
		private final long position;

		private final Buffer.DataType dataType;
		private final boolean isCompressed;
		private final int size;

		FileRawMessage(
			FileChannel fileChannel,
			long position,
			boolean isDataAvailable,
			boolean isEventAvailable,
			Buffer.DataType dataType,
			boolean isCompressed,
			int buffersInBacklog,
			int size) {

			super(isDataAvailable, isEventAvailable, buffersInBacklog);
			this.fileChannel = fileChannel;
			this.position = position;
			this.dataType = dataType;
			this.isCompressed = isCompressed;
			this.size = size;
		}

		@Override
		public NettyMessage buildMessage(InputChannelID id, int sequenceNumber) throws IOException {
			return new NettyMessage.BatchFileRegion(
				fileChannel,
				fileChannel.size(),
				dataType,
				isCompressed,
				sequenceNumber,
				id,
				buffersInBacklog,
				position,
				size);

		}
	}
}
