package org.anonymous.msrefactor.ms.notification;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.anonymous.msrefactor.ms.aggregate.Event;
import org.anonymous.msrefactor.ms.aggregate.EventSubscription;

import java.util.List;

@Service
public class EventService {
    @Autowired
    EventRepository eventRepository;

    public List<Event> getAllEvents() {
        return eventRepository.findAll();
    }

    public void saveEvent(Event event) {
        eventRepository.save(event);
    }

    public boolean existsEventById(Integer eventId) {
        return eventRepository.existsById(eventId);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public List<? extends Event> getSubscribedEvents(EventSubscription eventSubscription, Class<? extends Event> eventClass) {
        return eventRepository.findUnprocessedEvents(
                eventClass,
                eventSubscription.getSubscribedAggregateId(),
                eventSubscription.getSubscribedVersion()
        )
                .stream()
                .map(eventClass::cast)
                .filter(eventSubscription::subscribesEvent)
                .toList();
    }

    public void clearEventsAtApplicationStartUp() {
        eventRepository.deleteAll();
    }
}
