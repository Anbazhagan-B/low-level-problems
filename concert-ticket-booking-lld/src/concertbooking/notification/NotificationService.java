package concertbooking.notification;

import concertbooking.model.User;

/** Strategy seam: a family of interchangeable notification channels. */
public interface NotificationService {
    void notify(User user, String message);
}
