//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.websocket.util.messages;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.util.BlockingArrayQueue;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.core.Frame;

/**
 * Support class for reading a WebSocket BINARY message via a InputStream.
 * <p>
 * An InputStream that can access a queue of ByteBuffer payloads, along with expected InputStream blocking behavior.
 * </p>
 */
public class MessageInputStream extends InputStream implements MessageSink
{
    private static final Logger LOG = Log.getLogger(MessageInputStream.class);
    private static final Entry EOF = new Entry(BufferUtil.EMPTY_BUFFER, Callback.NOOP);
    private static final Entry CLOSED = new Entry(BufferUtil.EMPTY_BUFFER, Callback.NOOP);
    private final BlockingArrayQueue<Entry> buffers = new BlockingArrayQueue<>();
    private boolean closed = false;
    private Entry currentEntry;
    private long timeoutMs = -1;

    @Override
    public void accept(Frame frame, Callback callback)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("accepting {}", frame);

        boolean succeed = false;
        synchronized (this)
        {
            // If closed or we have no payload, request the next frame.
            if (closed || (!frame.hasPayload() && !frame.isFin()))
            {
                succeed = true;
            }
            else
            {
                if (frame.hasPayload())
                    buffers.add(new Entry(frame.getPayload(), callback));
                else
                    succeed = true;

                if (frame.isFin())
                    buffers.add(EOF);
            }
        }

        if (succeed)
            callback.succeeded();
    }

    @Override
    public int read() throws IOException
    {
        byte[] buf = new byte[1];
        while (true)
        {
            int len = read(buf, 0, 1);
            if (len < 0) // EOF
                return -1;
            if (len > 0) // did read something
                return buf[0];
            // reading nothing (len == 0) tries again
        }
    }

    @Override
    public int read(final byte[] b, final int off, final int len) throws IOException
    {
        return read(ByteBuffer.wrap(b, off, len).flip());
    }

    public int read(ByteBuffer buffer) throws IOException
    {
        Entry currentEntry = getCurrentEntry();
        if (LOG.isDebugEnabled())
            LOG.debug("currentEntry = {}", currentEntry);

        if (currentEntry == CLOSED)
            throw new IOException("Closed");

        if (currentEntry == EOF)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Read EOF");
            return -1;
        }

        // We have content.
        int fillLen = BufferUtil.append(buffer, currentEntry.buffer);
        if (!currentEntry.buffer.hasRemaining())
            succeedCurrentEntry();

        // Return number of bytes actually copied into buffer.
        if (LOG.isDebugEnabled())
            LOG.debug("filled {} bytes from {}", fillLen, currentEntry);
        return fillLen;
    }

    @Override
    public void close() throws IOException
    {
        if (LOG.isDebugEnabled())
            LOG.debug("close()");

        ArrayList<Entry> entries = new ArrayList<>();
        synchronized (this)
        {
            if (closed)
                return;
            closed = true;

            if (currentEntry != null)
            {
                entries.add(currentEntry);
                currentEntry = null;
            }

            // Clear queue and fail all entries.
            entries.addAll(buffers);
            buffers.clear();
            buffers.offer(CLOSED);
        }

        // Succeed all entries as we don't need them anymore (failing would close the connection).
        for (Entry e : entries)
        {
            e.callback.succeeded();
        }

        super.close();
    }

    public void setTimeout(long timeoutMs)
    {
        this.timeoutMs = timeoutMs;
    }

    private void succeedCurrentEntry()
    {
        Entry current;
        synchronized (this)
        {
            current = currentEntry;
            currentEntry = null;
        }
        if (current != null)
            current.callback.succeeded();
    }

    private Entry getCurrentEntry() throws IOException
    {
        synchronized (this)
        {
            if (currentEntry != null)
                return currentEntry;
        }

        try
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Waiting {} ms to read", timeoutMs);

            Entry result;
            if (timeoutMs < 0)
            {
                // Wait forever until a buffer is available.
                result = buffers.take();
            }
            else
            {
                // Wait at most for the given timeout.
                result = buffers.poll(timeoutMs, TimeUnit.MILLISECONDS);
                if (result == null)
                    throw new IOException(String.format("Read timeout: %,dms expired", timeoutMs));
            }

            synchronized (this)
            {
                currentEntry = result;
                return currentEntry;
            }
        }
        catch (InterruptedException e)
        {
            close();
            throw new InterruptedIOException();
        }
    }

    private static class Entry
    {
        public ByteBuffer buffer;
        public Callback callback;

        public Entry(ByteBuffer buffer, Callback callback)
        {
            this.buffer = Objects.requireNonNull(buffer);
            this.callback = callback;
        }

        @Override
        public String toString()
        {
            return String.format("Entry[%s,%s]", BufferUtil.toDetailString(buffer), callback.getClass().getSimpleName());
        }
    }
}
