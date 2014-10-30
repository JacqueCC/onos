/*
 * Copyright 2014 Open Networking Laboratory
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.onlab.packet;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Objects;
import static com.google.common.base.Preconditions.checkNotNull;

/**
 * A class representing an IPv4 address.
 */
public final class IpAddress implements Comparable<IpAddress> {
    // IP Versions
    public enum Version { INET, INET6 };

    // lengths of address, in bytes
    public static final int INET_BYTE_LENGTH = 4;
    public static final int INET_BIT_LENGTH = INET_BYTE_LENGTH * Byte.SIZE;
    public static final int INET6_BYTE_LENGTH = 16;
    public static final int INET6_BIT_LENGTH = INET6_BYTE_LENGTH * Byte.SIZE;

    private final Version version;
    private final byte[] octets;

    /**
     * Constructor for given IP address version and address octets.
     *
     * @param value the IP address value stored in network byte order
     * (i.e., the most significant byte first)
     * @param value the IP address value
     */
    private IpAddress(Version version, byte[] value) {
        checkNotNull(value);

        this.version = version;
        this.octets = Arrays.copyOf(value, INET_BYTE_LENGTH);
    }

    /**
     * Converts an integer into an IPv4 address.
     *
     * @param value an integer representing an IPv4 value
     * @return an IP address
     */
    public static IpAddress valueOf(int value) {
        byte[] bytes =
            ByteBuffer.allocate(INET_BYTE_LENGTH).putInt(value).array();
        return new IpAddress(Version.INET, bytes);
    }

    /**
     * Converts a byte array into an IP address.
     *
     * @param value the IP address value stored in network byte order
     * (i.e., the most significant byte first)
     * @return an IP address
     */
    public static IpAddress valueOf(byte[] value) {
        return new IpAddress(Version.INET, value);
    }

    /**
     * Converts a byte array and a given offset from the beginning of the
     * array into an IP address.
     * <p/>
     * The IP address is stored in network byte order (i.e., the most
     * significant byte first).
     *
     * @param value the value to use
     * @param offset the offset in bytes from the beginning of the byte array
     * @return an IP address
     */
    public static IpAddress valueOf(byte[] value, int offset) {
        // Verify the arguments
        if ((offset < 0) || (offset + INET_BYTE_LENGTH > value.length)) {
            String msg;
            if (value.length < INET_BYTE_LENGTH) {
                msg = "Invalid IPv4 address array: array length: " +
                    value.length + ". Must be at least " + INET_BYTE_LENGTH;
            } else {
                msg = "Invalid IPv4 address array: array offset: " +
                    offset + ". Must be in the interval [0, " +
                    (value.length - INET_BYTE_LENGTH) + "]";
            }
            throw new IllegalArgumentException(msg);
        }

        byte[] bc = Arrays.copyOfRange(value, offset, value.length);
        return IpAddress.valueOf(bc);
    }

    /**
     * Converts a dotted-decimal string (x.x.x.x) into an IPv4 address.
     *
     * @param address a IP address in string form, e.g. "10.0.0.1".
     * @return an IP address
     */
    public static IpAddress valueOf(String address) {
        final String[] net = address.split("\\.");
        if (net.length != INET_BYTE_LENGTH) {
            String msg = "Malformed IPv4 address string; " +
                "Address must have four decimal values separated by dots (.)";
            throw new IllegalArgumentException(msg);
        }
        final byte[] bytes = new byte[INET_BYTE_LENGTH];
        for (int i = 0; i < INET_BYTE_LENGTH; i++) {
            bytes[i] = (byte) Short.parseShort(net[i], 10);
        }
        return new IpAddress(Version.INET, bytes);
    }

    /**
     * Returns the IP version of this address.
     *
     * @return the version
     */
    public Version version() {
        return this.version;
    }

    /**
     * Returns the IP address as a byte array.
     *
     * @return a byte array
     */
    public byte[] toOctets() {
        return Arrays.copyOf(this.octets, INET_BYTE_LENGTH);
    }

    /**
     * Returns the integral value of this IP address.
     *
     * @return the IP address's value as an integer
     */
    public int toInt() {
        ByteBuffer bb = ByteBuffer.wrap(octets);
        return bb.getInt();
    }

    /**
     * Creates an IP network mask prefix.
     *
     * @param prefixLen the length of the mask prefix. Must be in the interval
     * [0, 32] for IPv4
     * @return a new IP address that contains a mask prefix of the
     * specified length
     */
    public static IpAddress makeMaskPrefix(int prefixLen) {
        // Verify the prefix length
        if ((prefixLen < 0) || (prefixLen > INET_BIT_LENGTH)) {
            final String msg = "Invalid IPv4 prefix length: " + prefixLen +
                ". Must be in the interval [0, 32].";
            throw new IllegalArgumentException(msg);
        }

        long v = (0xffffffffL << (INET_BIT_LENGTH - prefixLen)) & 0xffffffffL;
        return IpAddress.valueOf((int) v);
    }

    /**
     * Creates an IP address by masking it with a network mask of given
     * mask length.
     *
     * @param addr the address to mask
     * @param prefixLen the length of the mask prefix. Must be in the interval
     * [0, 32] for IPv4
     * @return a new IP address that is masked with a mask prefix of the
     * specified length
     */
    public static IpAddress makeMaskedAddress(final IpAddress addr,
                                              int prefixLen) {
        IpAddress mask = IpAddress.makeMaskPrefix(prefixLen);
        byte[] net = new byte[INET_BYTE_LENGTH];

        // Mask each byte
        for (int i = 0; i < INET_BYTE_LENGTH; i++) {
            net[i] = (byte) (addr.octets[i] & mask.octets[i]);
        }
        return IpAddress.valueOf(net);
    }

    @Override
    public int compareTo(IpAddress o) {
        Long lv = ((long) this.toInt()) & 0xffffffffL;
        Long rv = ((long) o.toInt()) & 0xffffffffL;
        return lv.compareTo(rv);
    }

    @Override
    public int hashCode() {
        return Objects.hash(version, octets);
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == this) {
            return true;
        }
        if ((obj == null) || (getClass() != obj.getClass())) {
            return false;
        }
        IpAddress other = (IpAddress) obj;
        return (version == other.version) &&
            Arrays.equals(octets, other.octets);
    }

    @Override
    /*
     * (non-Javadoc)
     * format is "x.x.x.x" for IPv4 addresses.
     *
     * @see java.lang.Object#toString()
     */
    public String toString() {
        final StringBuilder builder = new StringBuilder();
        for (final byte b : this.octets) {
            if (builder.length() > 0) {
                builder.append(".");
            }
            builder.append(String.format("%d", b & 0xff));
        }
        return builder.toString();
    }
}
