package bgu.spl.net.impl.stomp;

import java.util.HashMap;
import java.util.Map;

public class Frame {
    private String command;
    private Map<String, String> headers = new HashMap<>();
    private String body;

    public Frame(String message) {
        String[] lines = message.split("\n");
        int idx = 0;
        
        // 1. חילוץ הפקודה
        if (lines.length > 0) {
            this.command = lines[idx].trim();
            idx++;
        }

        // 2. חילוץ הכותרות
        while (idx < lines.length) {
            String line = lines[idx].trim();
            if (line.isEmpty()) { // שורה ריקה מסמנת את סוף הכותרות
                idx++;
                break;
            }
            
            String[] parts = line.split(":", 2);
            if (parts.length == 2) {
                headers.put(parts[0], parts[1]);
            }
            idx++;
        }

        // 3. חילוץ הגוף (כל מה שנשאר)
        StringBuilder bodyBuilder = new StringBuilder();
        while (idx < lines.length) {
            bodyBuilder.append(lines[idx]).append("\n");
            idx++;
        }
        this.body = bodyBuilder.toString().trim();
    }

    // גטרים שיהיה נוח להשתמש בפרוטוקול
    public String getCommand() { return command; }
    public String getHeader(String key) { return headers.get(key); }
    public String getBody() { return body; }
    
    // פונקציה לבניית המחרוזת חזרה (שימושי לשליחת תשובות)
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(command).append("\n");
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            sb.append(entry.getKey()).append(":").append(entry.getValue()).append("\n");
        }
        sb.append("\n");
        if (body != null && !body.isEmpty()) sb.append(body);
        sb.append("\u0000");
        return sb.toString();
    }
}