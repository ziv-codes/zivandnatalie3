package bgu.spl.net.impl.echo;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.Socket;

public class EchoClient {

    public static void main(String[] args) throws IOException {

        if (args.length == 0) {
            args = new String[]{"localhost", "hello"};
        }

        if (args.length < 2) {
            System.out.println("you must supply two arguments: host, message");
            System.exit(1);
        }

        //BufferedReader and BufferedWriter automatically using UTF-8 encoding
        try (Socket sock = new Socket(args[0], 7777);
                BufferedReader in = new BufferedReader(new InputStreamReader(sock.getInputStream()));
                BufferedWriter out = new BufferedWriter(new OutputStreamWriter(sock.getOutputStream()))) {

            System.out.println("sending message to server");

            String stompConnect = "CONNECT\n" +
                                  "accept-version:1.2\n" +
                                  "host:stomp.cs.bgu.ac.il\n" +
                                  "login:guest\n" +
                                  "passcode:guest\n" +
                                  "\n" + 
                                  "\u0000"; // <--- זה הדבר הכי חשוב! תו ה-Null

            System.out.println("sending CONNECT frame...");
            out.write(stompConnect);
            // out.newLine();
            out.flush();

            System.out.println("awaiting response");
            String line;
            while ((line = in.readLine()) != null) {
                System.out.println(line);
                if (line.isEmpty()) break; // בדרך כלל מפסיקים לקרוא אחרי ההדרים, אבל לטסט זה מספיק
                if (line.contains("\u0000")) break; // סיום הפריים
            }
        }
    }
}