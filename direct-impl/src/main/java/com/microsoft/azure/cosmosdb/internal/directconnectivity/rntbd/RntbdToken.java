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
 *
 */

package com.microsoft.azure.cosmosdb.internal.directconnectivity.rntbd;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.ser.PropertyWriter;
import com.fasterxml.jackson.databind.ser.impl.SimpleBeanPropertyFilter;
import com.google.common.base.Strings;
import io.netty.buffer.ByteBuf;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static com.microsoft.azure.cosmosdb.internal.directconnectivity.rntbd.RntbdConstants.RntbdHeader;

@JsonPropertyOrder({ "id", "name", "type", "present", "required", "value" })
final class RntbdToken {

    // region Fields

    private static final int HEADER_LENGTH = Short.BYTES + Byte.BYTES;

    static {
        RntbdObjectMapper.registerPropertyFilter(RntbdToken.class, RntbdToken.PropertyFilter.class);
    }

    private final RntbdHeader header;
    private int length;
    private Object value;

    // endregion

    // region Constructors

    private RntbdToken(final RntbdHeader header) {
        checkNotNull(header, "header");
        this.header = header;
        this.value = null;
        this.length = Integer.MIN_VALUE;
    }

    // endregion

    // region Accessors

    @JsonProperty
    public short getId() {
        return this.header.id();
    }

    @JsonProperty
    public String getName() {
        return this.header.name();
    }

    @JsonProperty
    public RntbdTokenType getTokenType() {
        return this.header.type();
    }

    @JsonProperty
    public Object getValue() {

        if (this.value == null) {
            return this.header.type().codec().defaultValue();
        }

        if (this.value instanceof ByteBuf) {
            final ByteBuf buffer = (ByteBuf)this.value;
            this.value = this.header.type().codec().read(buffer);
            buffer.release();
        } else {
            this.value = this.header.type().codec().convert(this.value);
        }

        return this.value;
    }

    public <T> T getValue(final Class<T> cls) {
        return cls.cast(this.getValue());
    }

    @JsonProperty
    public void setValue(final Object value) {
        this.ensureValid(value);
        this.releaseBuffer();
        this.value = value;
        this.length = Integer.MIN_VALUE;
    }

    @JsonIgnore
    public final Class<?> getValueType() {
        return this.header.type().codec().valueType();
    }

    @JsonProperty
    public boolean isPresent() {
        return this.value != null;
    }

    @JsonProperty
    public boolean isRequired() {
        return this.header.isRequired();
    }

    // endregion

    // region Methods

    public int computeLength() {

        if (!this.isPresent()) {
            return 0;
        }

        if (this.value instanceof ByteBuf) {
            final ByteBuf buffer = (ByteBuf)this.value;
            checkState(buffer.readerIndex() == 0);
            return HEADER_LENGTH + buffer.readableBytes();
        }

        if (this.length == Integer.MIN_VALUE) {
            this.length = HEADER_LENGTH + this.header.type().codec().computeLength(this.value);
        }

        return this.length;
    }

    public static RntbdToken create(final RntbdHeader header) {
        return new RntbdToken(header);
    }

    public void decode(final ByteBuf in) {

        checkNotNull(in, "in");

        if (this.value instanceof ByteBuf) {
            ((ByteBuf)this.value).release();
        }

        this.value = this.header.type().codec().readSlice(in).retain(); // No data transfer until first call to RntbdToken.getValue
    }

    public void encode(final ByteBuf out) {

        checkNotNull(out, "out");

        if (!this.isPresent()) {
            if (this.isRequired()) {
                final String message = Strings.lenientFormat("Missing value for required header: %s", this);
                throw new IllegalStateException(message);
            }
            return;
        }

        out.writeShortLE(this.getId());
        out.writeByte(this.getTokenType().id());

        if (this.value instanceof ByteBuf) {
            out.writeBytes((ByteBuf)this.value);
        } else {
            this.ensureValid(this.value);
            this.header.type().codec().write(this.value, out);
        }
    }

    public boolean releaseBuffer() {
        return this.value instanceof ByteBuf && ((ByteBuf)this.value).release();
    }

    @Override
    public String toString() {
        return RntbdObjectMapper.toString(this);
    }

    // endregion

    // region Privates

    private void ensureValid(final Object value) {
        checkArgument(value != null, "value: null");
        checkArgument(this.header.type().codec().isValid(value), "value: %s = %s", value.getClass().getName(), value);
    }

    // endregion

    // region Types

    static class PropertyFilter extends SimpleBeanPropertyFilter {

        @Override
        public void serializeAsField(final Object object, final JsonGenerator generator, final SerializerProvider provider, final PropertyWriter writer) throws Exception {

            if (generator.canOmitFields()) {

                final Object value = writer.getMember().getValue(object);

                if (value instanceof RntbdToken && !((RntbdToken)value).isPresent()) {
                    return;
                }
            }

            writer.serializeAsField(object, generator, provider);
        }
    }

    // endregion
}
