#pragma once

#include <string>
#include <map>
#include <vector>
#include <mutex>
#include <condition_variable>



class StompProtocol {
private:
    bool isLoggedIn;
    bool loginError; //
    bool logoutCompleted; //לסנכרון הניתוק
    int subCounter;
    int receiptCounter;

    // סנכרון תהליכונים
    std::mutex loginMutex;
    std::condition_variable loginCV;

    // ניהול מצב
    std::map<std::string, int> gameToSubId;    // שם משחק -> ID (עבור exit)
    std::map<int, std::string> subIdToGame;    // ID -> שם משחק (עבור הודעות MESSAGE)
    std::map<int, std::string> receiptToActions; // Receipt ID -> הודעה להדפסה

    std::map<std::string, std::map<std::string, std::vector<Event>>> game_to_user_events; // ממפה את הקשר בין משחק, ובתוך המשחק ישנם יוזרים שונים שעדכנו


public:
    StompProtocol();
    
    // פונקציות עיקריות
    std::string parseUserCommand(std::string input, std::string& userName);
    std::string processServerFrame(std::string frame);
    
    // סנכרון
    bool waitForLogin();
    bool waitForLogout();

    bool getIsLoggedIn() const;

    // ייצור פריימים
    std::string createConnectFrame(std::string host, std::string user, std::string pass);
    std::string createSubscribeFrame(std::string gameName);
    std::string createUnsubscribeFrame(std::string gameName);
    std::string createDisconnectFrame();
    std::string createSendFrame(const Event& event, const std::string& userName);
    
    void generateSummary(std::string gameName, std::string userName, std::string file);
};