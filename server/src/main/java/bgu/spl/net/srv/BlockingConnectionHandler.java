package bgu.spl.net.srv;

import bgu.spl.net.api.MessageEncoderDecoder;
import bgu.spl.net.api.MessagingProtocol;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.net.Socket;
import bgu.spl.net.api.StompMessagingProtocol;

public class BlockingConnectionHandler<T> implements Runnable, ConnectionHandler<T> {

    private final MessagingProtocol<T> protocol;
    private final StompMessagingProtocol<T> stompProtocol;
    private final MessageEncoderDecoder<T> encdec;
    private final Socket sock;
    private BufferedInputStream in;
    private BufferedOutputStream out;
    private volatile boolean connected = true;

    public BlockingConnectionHandler(Socket sock, MessageEncoderDecoder<T> reader, MessagingProtocol<T> protocol) {
        this.sock = sock;
        this.encdec = reader;
        this.protocol = protocol;
        this.stompProtocol = null;
    }

    public BlockingConnectionHandler(Socket sock, MessageEncoderDecoder<T> reader, StompMessagingProtocol<T> stompProtocol) {
        this.sock = sock;
        this.encdec = reader;
        this.protocol = null; // במקרה זה, הפרוטוקול הישן לא קיים
        this.stompProtocol = stompProtocol;
    }

    @Override
    public void run() {
        try (Socket sock = this.sock) { //just for automatic closing
            int read;

            in = new BufferedInputStream(sock.getInputStream());
            out = new BufferedOutputStream(sock.getOutputStream());

            while (!shouldTerminate() && connected && (read = in.read()) >= 0) {
                T nextMessage = encdec.decodeNextByte((byte) read);
                if (nextMessage != null) {
                    if (stompProtocol != null) {
                        // --- לוגיקה עבור STOMP ---
                        stompProtocol.process(nextMessage);
                    } else if (protocol != null) {
                        T response = protocol.process(nextMessage);
                        if (response != null) {
                            out.write(encdec.encode(response));
                            out.flush();
                        }
                    }
                }
            }

        } catch (IOException ex) {
            ex.printStackTrace();
        }
    }

    private boolean shouldTerminate() {
        if (stompProtocol != null) return stompProtocol.shouldTerminate();
        else if (protocol != null) return protocol.shouldTerminate();
        return false;
    }

    @Override
    public void close() throws IOException {
        connected = false;
        sock.close();
    }
    @Override
    public synchronized void send(T msg) {   
        if (msg != null) {
            try {
                byte[] encodedMsg = encdec.encode(msg);
                out.write(encodedMsg);                
                out.flush();
            } catch (IOException e) {
                // במקרה של שגיאת תקשורת, נדפיס את השגיאה
                e.printStackTrace();
            }
        }

    }
    public StompMessagingProtocol<T> getProtocol() {
        return stompProtocol;
    }
}
