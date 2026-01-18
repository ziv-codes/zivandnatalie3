package bgu.spl.net.impl.echo;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

public class EchoClient {

    public static void main(String[] args) throws IOException, InterruptedException {
        
        // הגדרות ברירת מחדל אם לא הוכנסו ארגומנטים
        String host = "127.0.0.1";
        int port = 7777;
        
        if (args.length > 0) host = args[0];
        if (args.length > 1) port = Integer.parseInt(args[1]);

        System.out.println("Connecting to " + host + ":" + port);

        try (Socket sock = new Socket(host, port);
             BufferedReader in = new BufferedReader(new InputStreamReader(sock.getInputStream(), StandardCharsets.UTF_8));
             BufferedWriter out = new BufferedWriter(new OutputStreamWriter(sock.getOutputStream(), StandardCharsets.UTF_8))) {

            System.out.println("--- Starting STOMP Test via EchoClient ---");

            // 1. שליחת פקודת CONNECT
            String connectFrame = "CONNECT\n" +
                                  "accept-version:1.2\n" +
                                  "host:stomp.cs.bgu.ac.il\n" +
                                  "login:natalie\n" +
                                  "passcode:coolpass\n" +
                                  "\n" + 
                                  "\u0000";
            send(out, connectFrame);
            System.out.println("Sent: CONNECT");
            readResponse(in); // מחכים לתשובה CONNECTED

            Thread.sleep(1000); // הפסקה קטנה כדי לראות בעיניים

            // 2. שליחת פקודת SEND עם דיווח על קובץ (בודק את ה-SQL)
            // שימי לב: זה החלק שבודק שזיו הוסיף את ההדר הנכון
            String sendFrame = "SEND\n" +
                               "destination:germany_spain\n" +
                               "file-name:games_data.txt\n" + // <--- זה ה-Header החשוב!
                               "\n" +
                               "Goal scored by Yamal!\n" + 
                               "\u0000";
            send(out, sendFrame);
            System.out.println("Sent: SEND (with file-name header)");
            // אין תשובה ל-SEND אלא אם ביקשנו receipt, אז לא מחכים כאן

            Thread.sleep(1000);

            // 3. שליחת פקודת DISCONNECT (בודק את עדכון היציאה)
            String disconnectFrame = "DISCONNECT\n" +
                                     "receipt:77\n" +
                                     "\n" + 
                                     "\u0000";
            send(out, disconnectFrame);
            System.out.println("Sent: DISCONNECT");
            readResponse(in); // מחכים ל-RECEIPT

            System.out.println("--- Test Finished Successfully ---");
        }
    }

    private static void send(BufferedWriter out, String msg) throws IOException {
        out.write(msg);
        out.flush();
    }

    private static void readResponse(BufferedReader in) throws IOException {
        System.out.println("Server Response:");
        int c;
        StringBuilder sb = new StringBuilder();
        // קוראים עד שמגיעים לתו ה-Null שמסמן סוף הודעה
        while ((c = in.read()) != -1) {
            if (c == '\u0000') break; 
            sb.append((char) c);
        }
        System.out.println(sb.toString());
        System.out.println("----------------");
    }
}