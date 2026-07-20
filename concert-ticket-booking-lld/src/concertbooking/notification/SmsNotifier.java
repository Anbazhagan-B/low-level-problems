package concertbooking.notification;

import concertbooking.model.User;

/** Second implementation showing OCP: a new channel needs no engine edits. */
public class SmsNotifier implements NotificationService {
    @Override
    public void notify(User user, String message) {
        System.out.println("SMS to " + user.getName() + ": " + message);
    }
}
