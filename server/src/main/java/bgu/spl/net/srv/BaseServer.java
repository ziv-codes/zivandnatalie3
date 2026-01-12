package bgu.spl.net.srv;

import bgu.spl.net.api.MessageEncoderDecoder;
import bgu.spl.net.api.MessagingProtocol;
import bgu.spl.net.api.StompMessagingProtocol;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.function.Supplier;

public abstract class BaseServer<T> implements Server<T> {

    private final int port;
    private final Supplier<MessagingProtocol<T>> protocolFactory;
    private final Supplier<MessageEncoderDecoder<T>> encdecFactory;
    private ServerSocket sock;

    // שדות חדשים עבוד STOMP
    private final Supplier<StompMessagingProtocol<T>> stompFactory;
    private final ConnectionsImpl<T> connections; 
    private int idCounter = 0;

    public BaseServer(
            int port,
            Supplier<MessagingProtocol<T>> protocolFactory,
            Supplier<MessageEncoderDecoder<T>> encdecFactory) {

        this.port = port;
        this.protocolFactory = protocolFactory;
        this.encdecFactory = encdecFactory;
		this.sock = null;
        this.stompFactory = null;
        this.connections = null;
    }

    // בנאי חדש עבור STOMP
    public BaseServer(
            int port,
            Supplier<StompMessagingProtocol<T>> stompFactory,
            Supplier<MessageEncoderDecoder<T>> encdecFactory,
            boolean isStomp) { // בוליאן דמי כדי ליצור חתימה שונה (Overloading)

        this.port = port;
        this.protocolFactory = null; // אין פרוטוקול ישן
        this.stompFactory = stompFactory;
        this.encdecFactory = encdecFactory;
        this.sock = null;
        
        // כאן יוצרים את המרכזייה המשותפת לכל הלקוחות
        this.connections = new ConnectionsImpl<>(); 
    }

    @Override
    public void serve() {

        try (ServerSocket serverSock = new ServerSocket(port)) {
			System.out.println("Server started");

            this.sock = serverSock; //just to be able to close

            while (!Thread.currentThread().isInterrupted()) {

                Socket clientSock = serverSock.accept();

                BlockingConnectionHandler<T> handler;
                if (stompFactory != null) {
                    // --- לוגיקה של STOMP ---
                    
                    // א. יצירת הפרוטוקול החדש
                    StompMessagingProtocol<T> protocol = stompFactory.get();
                   
                    handler = new BlockingConnectionHandler<>(
                            clientSock,
                            encdecFactory.get(),
                            protocol
                    );

                    int connectionId = idCounter++; // יצירת ID ייחודי
                    
                    // אתחול הפרוטוקול עם ה-ID והמרכזייה
                    protocol.start(connectionId, connections); 
                    
                    // הוספת ההנדלר למרכזייה כדי שיוכל לקבל הודעות מאחרים
                    connections.addConnection(connectionId, handler);
                    
                }
                else{ 
                    handler= new BlockingConnectionHandler<>(
                        clientSock,
                        encdecFactory.get(),
                        protocolFactory.get());
                }

                execute(handler);
            }
        } catch (IOException ex) {
            //TODO: ZIV?? print something?
        }

        System.out.println("server closed!!!");
    }

    @Override
    public void close() throws IOException {
		if (sock != null)
			sock.close();
    }

    protected abstract void execute(BlockingConnectionHandler<T>  handler);

}
