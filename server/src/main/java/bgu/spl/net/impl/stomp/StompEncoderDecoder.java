package bgu.spl.net.impl.stomp;

import bgu.spl.net.api.MessageEncoderDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public class StompEncoderDecoder implements MessageEncoderDecoder<String> {

     private byte[] bytes = new byte[1 << 10]; //start with 1k
    private int len = 0;

    @Override
    public String decodeNextByte(byte nextByte) {
        // שינוי קריטי: בודקים אם הגענו לתו ה-Null (\u0000) במקום ל-\n
        if (nextByte == '\u0000') {
            return popString();
        }

        pushByte(nextByte);
        return null; // עדיין לא סיימנו לקרוא את ההודעה
    }

    @Override
    public byte[] encode(String message) {
        // המרה של המחרוזת לבתים + הוספת תו הסיום \0 בסוף (חובה לפי הפרוטוקול)
        return (message + "\u0000").getBytes(StandardCharsets.UTF_8);
    }

    private void pushByte(byte nextByte) {
        if (len >= bytes.length) {
            bytes = Arrays.copyOf(bytes, len * 2);
        }
        bytes[len++] = nextByte;
    }

    private String popString() {
        // יצירת המחרוזת מהבתים שאגרנו
        String result = new String(bytes, 0, len, StandardCharsets.UTF_8);
        len = 0;
        return result;
    }
}