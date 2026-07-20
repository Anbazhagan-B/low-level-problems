package concertbooking.notification;

import concertbooking.model.User;

public class EmailNotifier implements NotificationService {
    @Override
    public void notify(User user, String message) {
        System.out.println("Email to " + user.getEmail() + ": " + message);
    }
}
