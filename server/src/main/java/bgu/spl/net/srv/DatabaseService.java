package bgu.spl.net.srv;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

public class DatabaseService {
    private final String host = "127.0.0.1";
    private final int port = 7778; // הפורט של שרת הפייתון

    /**
     * פונקציית העזר ששולחת פקודת SQL לפייתון ומחזירה את התשובה.
     */
    public synchronized String execute(String sqlCommand) {
        try (Socket socket = new Socket(host, port);
             BufferedWriter out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
             BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {

            // שליחת הפקודה
            out.write(sqlCommand);
            out.write('\0'); // תו סיום הודעה כפי שהפייתון מצפה
            out.flush();

            // קריאת התשובה
            StringBuilder response = new StringBuilder();
            int c;
            while ((c = in.read()) != -1) {
                if (c == '\0') break; // סוף תשובה
                response.append((char) c);
            }
            return response.toString();

        } catch (IOException e) {
            e.printStackTrace();
            return "Error: " + e.getMessage();
        }
    }

    /**
     * Prints the required server-side report using SQL queries.
     * Requirements: 
     */
    public void printReport() {
        System.out.println("--- Server Data Report ---");
        
        // 1. רשימת משתמשים רשומים
        System.out.println("\n[Registered Users]:");
        String users = execute("SELECT username FROM Users");
        System.out.println(users);

        // 2. היסטוריית התחברויות (משתמש, זמן כניסה, זמן יציאה)
        System.out.println("\n[Login History]:");
        String history = execute("SELECT username, login_time, logout_time FROM Login_History");
        System.out.println(history);

        // 3. קבצים שהועלו
        System.out.println("\n[Uploaded Files]:");
        String files = execute("SELECT username, filename, upload_time FROM Uploaded_Files");
        System.out.println(files);
        
        System.out.println("--------------------------");
    }
}