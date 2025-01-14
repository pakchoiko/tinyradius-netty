package org.tinyradius.packet;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.socket.DatagramPacket;
import org.junit.jupiter.api.Test;
import org.tinyradius.dictionary.DefaultDictionary;
import org.tinyradius.dictionary.Dictionary;
import org.tinyradius.util.RadiusException;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Collections;

import static java.lang.Byte.toUnsignedInt;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;
import static org.tinyradius.attribute.Attributes.createAttribute;
import static org.tinyradius.packet.PacketType.*;
import static org.tinyradius.packet.RadiusPacket.HEADER_LENGTH;
import static org.tinyradius.packet.RadiusPackets.createRadiusPacket;

class PacketEncoderTest {

    private static final SecureRandom random = new SecureRandom();
    private static Dictionary dictionary = DefaultDictionary.INSTANCE;
    private static PacketEncoder packetEncoder = new PacketEncoder(dictionary);
    private static final InetSocketAddress remoteAddress = new InetSocketAddress(0);

    @Test
    void nextPacketId() {
        for (int i = 0; i < 1000; i++) {
            final int next = RadiusPackets.nextPacketId();
            assertTrue(next < 256);
            assertTrue(next >= 0);
        }
    }

    private void addBytesToPacket(RadiusPacket packet, int targetSize) {
        int dataSize = targetSize - HEADER_LENGTH;
        for (int i = 0; i < Math.floor(dataSize / 200); i++) {
            // add 200 octets per iteration (198 + 2-byte header)
            packet.addAttribute(createAttribute(dictionary, -1, 33, random.generateSeed(198)));
        }
        packet.addAttribute(createAttribute(dictionary, -1, 33, random.generateSeed((dataSize % 200) - 2)));
    }

    @Test
    void toDatagramMaxPacketSize() throws RadiusException {
        // test max length 4096
        RadiusPacket maxSizeRequest = new RadiusPacket(dictionary, 200, 250);
        addBytesToPacket(maxSizeRequest, 4096);

        final ByteBuf byteBuf = packetEncoder
                .toDatagram(maxSizeRequest.encodeRequest("mySecret"), new InetSocketAddress(0))
                .content();

        assertEquals(4096, byteBuf.readableBytes());
        assertEquals(4096, byteBuf.getShort(2));

        // test length 4097
        RadiusPacket oversizeRequest = new RadiusPacket(dictionary, 200, 250);
        addBytesToPacket(oversizeRequest, 4097);

        final RadiusException exception = assertThrows(RadiusException.class,
                () -> packetEncoder.toDatagram(oversizeRequest.encodeRequest("mySecret"), new InetSocketAddress(0)));

        assertTrue(exception.getMessage().toLowerCase().contains("packet too long"));

        byteBuf.release();
    }

    @Test
    void toDatagram() throws RadiusException {
        final InetSocketAddress address = new InetSocketAddress(random.nextInt(65535));
        RadiusPacket request = new RadiusPacket(dictionary, 200, 250);

        final byte[] proxyState = random.generateSeed(198);
        request.addAttribute(createAttribute(dictionary, -1, 33, proxyState));
        request.addAttribute(createAttribute(dictionary, -1, 33, random.generateSeed(198)));

        final RadiusPacket encoded = request.encodeRequest("mySecret");

        DatagramPacket datagram = packetEncoder.toDatagram(encoded, address);

        assertEquals(address, datagram.recipient());

        // packet
        final byte[] packet = datagram.content().array();
        assertEquals(420, packet.length);

        assertEquals(encoded.getType(), toUnsignedInt(packet[0]));
        assertEquals(encoded.getIdentifier(), toUnsignedInt(packet[1]));
        assertEquals(packet.length, ByteBuffer.wrap(packet).getShort(2));
        assertArrayEquals(encoded.getAuthenticator(), Arrays.copyOfRange(packet, 4, 20));

        // attribute
        final byte[] attributes = Arrays.copyOfRange(packet, 20, packet.length);
        assertEquals(400, attributes.length); // 2x 2-octet header + 2x 198

        assertEquals(33, attributes[0]);
        assertArrayEquals(proxyState, Arrays.copyOfRange(attributes, 2, toUnsignedInt(attributes[1])));

        datagram.release();
    }

    @Test
    void fromMaxSizeRequestDatagram() throws RadiusException {
        String sharedSecret = "sharedSecret1";

        // test max length 4096
        AccountingRequest rawRequest = new AccountingRequest(dictionary, 250, null);
        addBytesToPacket(rawRequest, 4096);
        final RadiusPacket maxSizeRequest = rawRequest.encodeRequest(sharedSecret);

        final DatagramPacket datagram = packetEncoder.toDatagram(maxSizeRequest, new InetSocketAddress(0));
        assertEquals(4096, datagram.content().readableBytes());

        RadiusPacket result = packetEncoder.fromDatagram(datagram, sharedSecret);

        assertEquals(maxSizeRequest.getType(), result.getType());
        assertEquals(maxSizeRequest.getIdentifier(), result.getIdentifier());
        assertArrayEquals(maxSizeRequest.getAuthenticator(), result.getAuthenticator());
        assertArrayEquals(maxSizeRequest.getAttributeBytes(), result.getAttributeBytes());

        assertEquals(maxSizeRequest.getAttributes(33).size(), result.getAttributes(33).size());

        // reconvert to check if bytes match
        assertArrayEquals(datagram.content().array(), packetEncoder.toDatagram(result, new InetSocketAddress(0)).content().array());
    }

    @Test
    void fromOverSizeRequestDatagram() throws RadiusException {
        String sharedSecret = "sharedSecret1";

        // make 4090 octet packet
        AccountingRequest packet = new AccountingRequest(dictionary, 250, null);
        addBytesToPacket(packet, 4090);

        final byte[] validBytes = packetEncoder
                .toDatagram(packet.encodeRequest(sharedSecret), new InetSocketAddress(0))
                .content().copy().array();
        assertEquals(4090, validBytes.length);

        // create 7 octet attribute
        final byte[] attribute = createAttribute(dictionary, -1, 33, random.generateSeed(5)).toByteArray();
        assertEquals(7, attribute.length);

        final ByteBuf buffer = Unpooled.buffer(4097, 4097);
        buffer.writeBytes(validBytes);

        // manually append attribute
        buffer.writeBytes(attribute);
        buffer.setShort(2, 4097);

        final RadiusException exception = assertThrows(RadiusException.class,
                () -> packetEncoder.fromDatagram(new DatagramPacket(buffer, new InetSocketAddress(0)), sharedSecret));

        assertTrue(exception.getMessage().contains("packet too long"));
    }

    @Test
    void accountingRequestFromDatagram() throws RadiusException {
        String user = "user1";
        String sharedSecret = "sharedSecret1";

        AccountingRequest rawRequest = new AccountingRequest(dictionary, 250, null);
        rawRequest.setUserName(user);
        final RadiusPacket request = rawRequest.encodeRequest(sharedSecret);

        DatagramPacket datagramPacket = packetEncoder.toDatagram(request, remoteAddress);
        RadiusPacket packet = packetEncoder.fromDatagram(datagramPacket, sharedSecret);

        assertEquals(ACCOUNTING_REQUEST, packet.getType());
        assertTrue(packet instanceof AccountingRequest);
        assertEquals(rawRequest.getIdentifier(), packet.getIdentifier());
        assertEquals(rawRequest.getUserName(), packet.getAttribute("User-Name").getValueString());
    }

    @Test
    void accountingRequestBadAuthFromDatagram() throws RadiusException {
        String user = "user1";
        String sharedSecret = "sharedSecret1";

        AccountingRequest rawRequest = new AccountingRequest(dictionary, 250, null);
        rawRequest.setUserName(user);
        final RadiusPacket request = rawRequest.encodeRequest(sharedSecret);

        DatagramPacket originalDatagram = packetEncoder.toDatagram(request, remoteAddress);

        final byte[] array = originalDatagram.content().array();
        array[4] = 0; // corrupt authenticator

        final DatagramPacket datagramPacket = new DatagramPacket(Unpooled.wrappedBuffer(array), originalDatagram.recipient());

        final RadiusException radiusException = assertThrows(RadiusException.class,
                () -> packetEncoder.fromDatagram(datagramPacket, sharedSecret));

        assertTrue(radiusException.getMessage().toLowerCase().contains("authenticator check failed"));
    }

    @Test
    void accessRequestFromDatagram() throws RadiusException {
        String user = "user1";
        String password = "myPassword";
        String sharedSecret = "sharedSecret1";

        AccessRequest rawRequest = new AccessRequest(dictionary, 250, null, user, password);
        final RadiusPacket request = rawRequest.encodeRequest(sharedSecret);

        DatagramPacket datagramPacket = packetEncoder.toDatagram(request, remoteAddress);
        RadiusPacket radiusPacket = packetEncoder.fromDatagram(datagramPacket, sharedSecret);

        assertEquals(ACCESS_REQUEST, radiusPacket.getType());
        assertTrue(radiusPacket instanceof AccessRequest);

        AccessRequest packet = (AccessRequest) radiusPacket;
        assertEquals(rawRequest.getIdentifier(), packet.getIdentifier());
        assertEquals(rawRequest.getUserName(), packet.getAttribute("User-Name").getValueString());
        assertEquals(rawRequest.getUserPassword(), packet.getUserPassword());
    }

    @Test
    void fromResponseDatagram() throws RadiusException {
        String user = "user2";
        String plaintextPw = "myPassword2";
        String sharedSecret = "sharedSecret2";

        final int id = random.nextInt(256);

        final AccessRequest request = new AccessRequest(dictionary, id, null, user, plaintextPw);
        final AccessRequest encodedRequest = request.encodeRequest(sharedSecret);

        final RadiusPacket response = new RadiusPacket(dictionary, 2, id);
        response.addAttribute(createAttribute(dictionary, -1, 33, "state3333".getBytes(UTF_8)));
        final RadiusPacket encodedResponse = response.encodeResponse(sharedSecret, encodedRequest.getAuthenticator());

        DatagramPacket datagramPacket = packetEncoder.toDatagram(encodedResponse, remoteAddress);
        RadiusPacket packet = packetEncoder.fromDatagram(datagramPacket, sharedSecret, encodedRequest);

        assertEquals(encodedResponse.getIdentifier(), packet.getIdentifier());
        assertEquals("state3333", new String(packet.getAttribute(33).getValue()));
        assertArrayEquals(encodedResponse.getAuthenticator(), packet.getAuthenticator());
    }

    @Test
    void createRequestRadiusPacket() {
        RadiusPacket accessRequest = createRadiusPacket(dictionary, ACCESS_REQUEST, 1, null, Collections.emptyList());
        RadiusPacket coaRequest = createRadiusPacket(dictionary, COA_REQUEST, 2, null, Collections.emptyList());
        RadiusPacket accountingRequest = createRadiusPacket(dictionary, ACCOUNTING_REQUEST, 3, null, Collections.emptyList());

        assertEquals(ACCESS_REQUEST, accessRequest.getType());
        assertEquals(AccessRequest.class, accessRequest.getClass());

        assertEquals(COA_REQUEST, coaRequest.getType());
        assertEquals(RadiusPacket.class, coaRequest.getClass());

        assertEquals(ACCOUNTING_REQUEST, accountingRequest.getType());
        assertEquals(AccountingRequest.class, accountingRequest.getClass());
    }
}