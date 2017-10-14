/*
 * This file is part of picam.
 *
 * picam is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * picam is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with picam.  If not, see <http://www.gnu.org/licenses/>.
 *
 * Copyright 2016 Caprica Software Limited.
 */

package uk.co.caprica.picam;

import static uk.co.caprica.picam.bindings.LibMmal.mmal;
import static uk.co.caprica.picam.bindings.internal.MMAL_BUFFER_HEADER_FLAG.MMAL_BUFFER_HEADER_FLAG_FRAME_END;
import static uk.co.caprica.picam.bindings.internal.MMAL_BUFFER_HEADER_FLAG.MMAL_BUFFER_HEADER_FLAG_TRANSMISSION_FAILED;
import static uk.co.caprica.picam.bindings.internal.MMAL_STATUS_T.MMAL_SUCCESS;

import java.util.concurrent.CountDownLatch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.sun.jna.Pointer;

import uk.co.caprica.picam.bindings.internal.MMAL_BUFFER_HEADER_T;
import uk.co.caprica.picam.bindings.internal.MMAL_POOL_T;
import uk.co.caprica.picam.bindings.internal.MMAL_PORT_BH_CB_T;
import uk.co.caprica.picam.bindings.internal.MMAL_PORT_T;

final class EncoderBufferCallback implements MMAL_PORT_BH_CB_T {

    private final Logger logger = LoggerFactory.getLogger(EncoderBufferCallback.class);

    private final PictureCaptureHandler pictureCaptureHandler;

    private final CountDownLatch captureFinishedLatch;

    private final MMAL_POOL_T picturePool;

    EncoderBufferCallback(PictureCaptureHandler pictureCaptureHandler, CountDownLatch captureFinishedLatch, MMAL_POOL_T picturePool) {
        this.pictureCaptureHandler = pictureCaptureHandler;
        this.captureFinishedLatch = captureFinishedLatch;
        this.picturePool = picturePool;
    }

    @Override
    public void apply(Pointer portPointer, Pointer bufferPointer) {
        logger.debug("apply()");

        logger.trace("port={}", portPointer);
        logger.trace("buffer={}", bufferPointer);

        boolean finished = false;
        
        MMAL_PORT_T port = new MMAL_PORT_T(portPointer);
        port.read();
        logger.trace("port={}", port);
        
        MMAL_BUFFER_HEADER_T buffer = new MMAL_BUFFER_HEADER_T(bufferPointer);
        buffer.read();
        logger.trace("buffer={}", buffer);
        
        int bufferLength = buffer.length;
        logger.debug("bufferLength={}", bufferLength);

        if (bufferLength > 0) {
        	mmal.mmal_buffer_header_mem_lock(bufferPointer);
            try {
                // interface callback with the bytes
                byte[] data = buffer.data.getByteArray(buffer.offset, bufferLength);
                pictureCaptureHandler.pictureData(data);
            }
            catch (Exception e) {
                logger.error("Error in callback handling picture data", e);
                finished = true;
            }
            finally {
            	mmal.mmal_buffer_header_mem_unlock(bufferPointer);
            }
        }

        int flags = buffer.flags;
        logger.debug("flags={}", flags);

        if ((buffer.flags & (MMAL_BUFFER_HEADER_FLAG_FRAME_END | MMAL_BUFFER_HEADER_FLAG_TRANSMISSION_FAILED)) != 0) {
            finished = true;
        }

        mmal.mmal_buffer_header_release(bufferPointer);

        if (!finished && port.isEnabled()) {
            sendNextPictureBuffer(portPointer);
        }

        logger.debug("finished={}", finished);

        if (finished) {
            logger.debug("signal capture complete");
            captureFinishedLatch.countDown();
        }
    }

    private void sendNextPictureBuffer(Pointer portPointer) {
        logger.debug("sendNextPictureBuffer()");

        MMAL_BUFFER_HEADER_T nextBuffer = mmal.mmal_queue_get(picturePool.queue);
        logger.trace("nextBuffer={}", nextBuffer);

        if (nextBuffer == null) {
            throw new RuntimeException("Failed to get next buffer from picture pool");
        }

        int result = mmal.mmal_port_send_buffer(portPointer, nextBuffer.getPointer());
        logger.debug("result={}", result);

        if (result != MMAL_SUCCESS) {
            throw new RuntimeException("Failed to send next picture buffer to encoder");
        }
    }
}
