package bgu.spl.net.impl.stomp;

import bgu.spl.net.srv.Server;

public class StompServer {

    public static void main(String[] args) {
        // בדיקה שיש מספיק ארגומנטים (פורט וסוג שרת)
        if (args.length < 2) {
            System.out.println("Usage: StompServer <port> <server_type(tpc/reactor)>");
            return;
        }

        // המרת הפורט למספר
        int port = Integer.parseInt(args[0]);
        String serverType = args[1];

        // בדיקה איזה שרת להפעיל לפי הקלט
        if (serverType.equals("tpc")) {
            Server.threadPerClient(
                    port,
                    () -> new StompMessagingProtocolImpl(), // יצירת פרוטוקול לכל לקוח
                    () -> new StompEncoderDecoder(),        // יצירת מפענח לכל לקוח
                    true                                    // שימוש בבנאי החדש (STOMP)
            ).serve();

        } else if (serverType.equals("reactor")) {
            Server.reactor(
                    Runtime.getRuntime().availableProcessors(), // מספר הת'רדים
                    port,
                    () -> new StompMessagingProtocolImpl(),
                    () -> new StompEncoderDecoder(),
                    true                                    // שימוש בבנאי החדש (STOMP)
            ).serve();

        } else {
            System.out.println("Unknown server type. Use 'tpc' or 'reactor'.");
        }
    }
}