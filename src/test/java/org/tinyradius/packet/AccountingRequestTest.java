package org.tinyradius.packet;

import net.jradius.util.RadiusUtils;
import org.junit.jupiter.api.Test;
import org.tinyradius.dictionary.DefaultDictionary;
import org.tinyradius.dictionary.Dictionary;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.tinyradius.attribute.Attributes.createAttribute;
import static org.tinyradius.packet.AccountingRequest.ACCT_STATUS_TYPE_ACCOUNTING_ON;
import static org.tinyradius.packet.PacketType.ACCOUNTING_REQUEST;
import static org.tinyradius.packet.RadiusPacket.HEADER_LENGTH;

class AccountingRequestTest {

    private static Dictionary dictionary = DefaultDictionary.INSTANCE;

    @Test
    void encodeAccountingRequest() {

        String sharedSecret = "sharedSecret";
        String user = "myUser1";
        AccountingRequest request = new AccountingRequest(dictionary, 1, null);
        request.addAttribute(createAttribute(dictionary, -1, 1, user.getBytes(UTF_8)));

        final byte[] attributeBytes = request.getAttributeBytes();
        final int length = attributeBytes.length + HEADER_LENGTH;
        final byte[] expectedAuthenticator = RadiusUtils.makeRFC2866RequestAuthenticator(
                sharedSecret, (byte) ACCOUNTING_REQUEST, (byte) 1, length, attributeBytes, 0, attributeBytes.length);

        RadiusPacket encoded = request.encodeRequest(sharedSecret);

        assertEquals(request.getType(), encoded.getType());
        assertEquals(request.getIdentifier(), encoded.getIdentifier());
        assertEquals(request.getAttributes(), encoded.getAttributes());
        assertArrayEquals(expectedAuthenticator, encoded.getAuthenticator());
    }

    @Test
    void encodeNewAccountingRequestWithUsernameAndAcctStatus() {
        String sharedSecret = "sharedSecret";
        String user = "myUser1";
        AccountingRequest request = new AccountingRequest(dictionary, 1, null, user, 7);

        AccountingRequest encoded = (AccountingRequest) request.encodeRequest(sharedSecret);
        assertEquals(request.getUserName(), encoded.getUserName());
        assertEquals(ACCT_STATUS_TYPE_ACCOUNTING_ON, encoded.getAcctStatusType());
        assertEquals(request.getAcctStatusType(), encoded.getAcctStatusType());
    }
}