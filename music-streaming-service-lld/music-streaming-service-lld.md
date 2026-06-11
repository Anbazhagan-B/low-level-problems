# Music Streaming Service (Spotify-like) — Low-Level Design

**Tier:** Hard | **Language:** Java 11+ | **Scope:** In-memory LLD (control plane only — no real audio I/O, persistence, or CDN)

---

## Step 1 — Requirements

### Functional Requirements

| # | Requirement | Notes |
|---|-------------|-------|
| F1 | Browse & search songs, albums, artists | Case-insensitive substring match over in-memory indexes |
| F2 | Create & manage playlists | Create, rename, delete; add/remove/reorder songs; owned by one user |
| F3 | Authentication & authorization | Register/login → session token; tier-gated features |
| F4 | Playback control | Play, pause, resume, skip, seek; one active session per user |
| F5 | Recommendations | Pluggable algorithm over listening history & global popularity |
| F6 | Listening history | Every play event recorded per user (feeds F5) |
| F7 | Subscription tiers | FREE (skip limit, ads) vs PREMIUM (unrestricted) |

### Non-Functional Requirements

| # | Requirement | LLD Translation |
|---|-------------|-----------------|
| N1 | Concurrency | Per-resource locking on playlists & playback sessions; lock-free counters for play counts; `ConcurrentHashMap` registries |
| N2 | Scalability | O(1) ID lookups via maps; search index isolated behind `MusicLibrary` so it can be swapped for a trie/inverted index |
| N3 | Extensibility | Strategy for recommendations & tier rules; Observer for play events (social sharing, analytics plug in without modification) |
| N4 | Smooth streaming | Modeled as *correct, thread-safe playback state transitions*; buffering/bitrate/CDN are infrastructure, out of LLD scope |

### Locked-In Assumptions

- Song belongs to exactly one album; album to one artist. Playlist ↔ Song is many-to-many.
- Playlists are private (collaborative playlists = follow-up question).
- One active `PlaybackSession` per user (multi-device = follow-up question).
- Recommendation quality is secondary; the **interface** is what's being tested.

---

## Step 2 — Entities & Relationships

### Core Entities

| Entity | Responsibility | Relationship Notes |
|--------|---------------|--------------------|
| `User` | Identity, tier, owns playlists & history | **Composition** with `ListeningHistory` (dies with user); **aggregation** of `Playlist` |
| `Artist` | Catalog metadata | **Aggregation** of `Album` (albums conceptually outlive an artist object in the catalog) |
| `Album` | Groups songs, release metadata | **Aggregation** of `Song` |
| `Song` | Title, duration, genre, play count | **Association** to `Album`/`Artist` by ID (avoids deep object graphs) |
| `Playlist` | Ordered, mutable song list | **Association** to `Song` (shared references — a song lives in many playlists) |
| `PlaybackSession` | Per-user player: current song, state, position, queue | **Composition** inside the service, keyed by user; **dependency** on `PlaybackRules` |
| `ListeningHistory` / `PlayEvent` | Append-only log of plays | Feeds recommendations |
| `MusicLibrary` | Catalog registry + search | Repository-style; the only thing that knows how search works |
| `UserManager` | Registration, login, token → user | Auth isolated from playback logic |
| `MusicStreamingService` | Facade / coordinator | Singleton |

### Enums (state & classification — not classes)

- `SubscriptionTier { FREE, PREMIUM }`
- `PlaybackState { STOPPED, PLAYING, PAUSED }`
- `Genre { POP, ROCK, JAZZ, CLASSICAL, HIPHOP, ELECTRONIC }`

### Behavioral Interfaces

- `RecommendationStrategy` → `GenreAffinityStrategy`, `TrendingStrategy`
- `PlaybackRules` → `FreeTierRules`, `PremiumTierRules`
- `PlaybackListener` (Observer) → `HistoryRecorder`, `PlayCountUpdater`

### Deliberate Under-Modeling (interview gold)

- **No `Like` / `Follow` / `Stream` entity classes.** A play is a `PlayEvent` record; popularity is a `LongAdder` on `Song`. Don't reify what a counter or log entry expresses.
- **No `Queue` entity.** The playback queue is just a `Deque<Song>` inside `PlaybackSession` — it has no identity or lifecycle of its own.
- **IDs, not object references, across aggregate boundaries** (`Song.albumId` rather than `Song.album`). Mirrors how you'd shard at scale and avoids accidental deep mutation. This is the same *catalog-vs-instance discipline* from Library (`Book`/`BookCopy`) and Car Rental.

---

## Step 3 — UML Class Design

```
+--------------------------+        +---------------------------+
| MusicStreamingService    |<>------| MusicLibrary              |
| (Singleton, Facade)      |        | songs: Map<String,Song>   |
|--------------------------|        | albums: Map<String,Album> |
| userManager              |        | artists: Map<String,Artist>|
| library                  |        |---------------------------|
| sessions: Map<userId,    |        | searchSongs(q)            |
|            PlaybackSession>       | searchAlbums(q)           |
| listeners: CopyOnWrite-  |        | searchArtists(q)          |
|            ArrayList     |        +---------------------------+
| recommender: Recommend-  |
|              ationStrategy|              +------------------+
|--------------------------|              | UserManager      |
| play(token, songId)      |<>------------| usersByEmail     |
| pause/resume/skip/seek   |              | tokens: Map      |
| createPlaylist(...)      |              |------------------|
| recommend(token, n)      |              | register/login   |
+--------------------------+              | authenticate(tok)|
                                          +------------------+
        | uses                                     |
        v                                          v 1
+---------------------------+            +------------------+
| PlaybackSession           |  1     1   | User             |
|---------------------------|------------|------------------|
| user, currentSong         |            | id, email, tier  |
| state: PlaybackState      |            | playlists: Map   |
| positionSec, queue:Deque  |            | history (1..1 ◆) |
| rules: PlaybackRules      |            +------------------+
| lock: ReentrantLock       |                     | owns 0..*
|---------------------------|                     v
| play(song) pause() resume()|           +------------------+
| skip() seek(sec)          |            | Playlist         |
+---------------------------+            | songs: List<Song>|
        | notifies                       | lock: ReentrantLock
        v                                +------------------+
<<interface>> PlaybackListener                    | 0..*
   ^            ^                                 v
HistoryRecorder PlayCountUpdater         +------------------+
                                         | Song             |
<<interface>> RecommendationStrategy     | id,title,genre   |
   ^                ^                    | albumId,artistId |
GenreAffinity   Trending                 | playCount:LongAdder
                                         +------------------+
<<interface>> PlaybackRules                Album ◇— Song
   ^              ^                        Artist ◇— Album
FreeTierRules  PremiumTierRules
```

### Pattern → Justification Map

| Pattern | Where | Why it fits (the part interviewers want) |
|---------|-------|------------------------------------------|
| **Singleton** (holder idiom) | `MusicStreamingService` | One coordinator owning shared registries. Holder idiom gives lazy init + thread safety with zero locking — JVM class-loading guarantees do the work. |
| **Facade** | `MusicStreamingService` | Clients see `play(token, songId)`, not the auth→session→rules→listener choreography behind it. |
| **Strategy** | `RecommendationStrategy` | Recommendation algorithms vary independently of the playback machinery (OCP). Swapping genre-affinity for collaborative filtering touches zero existing classes. |
| **Strategy** (again) | `PlaybackRules` | Tier behavior (skip limits, ads) is *policy*, not *mechanism*. An `if (tier == FREE)` scattered through `PlaybackSession` would violate OCP the moment a STUDENT tier appears. |
| **Observer** | `PlaybackListener` | A play event fans out to history, play counts — later social sharing, ad analytics — without `PlaybackSession` knowing any of them exist. This is exactly your F8 extensibility requirement. |
| **State (lightweight)** | `PlaybackState` enum + guarded transitions | Three states with simple legal-transition rules don't justify four GoF state classes. Enum-guarded transitions (your Facebook/Auction precedent) keep it honest without ceremony. |
| **Repository** | `MusicLibrary`, `UserManager` | Persistence-shaped seams: swap in-memory maps for a DB without touching domain logic (DIP). |

### SOLID Mapping

- **S** — `UserManager` does auth, `MusicLibrary` does catalog, `PlaybackSession` does playback. None knows the others' internals.
- **O** — New recommendation algorithm, new tier, new play-event consumer: all additions, no modifications.
- **L** — Any `PlaybackRules` is substitutable; `PlaybackSession` never downcasts or special-cases.
- **I** — `PlaybackListener` is one method; consumers aren't forced to implement search or auth callbacks they don't need.
- **D** — `PlaybackSession` depends on the `PlaybackRules` abstraction; the service depends on `RecommendationStrategy`, not a concrete recommender.

### The Two Decisions an Interviewer Will Probe

1. **Why is `PlaybackSession` locked at all if there's one session per user?** Because "one user" ≠ "one thread": mobile + web client, or a UI thread and a track-finished callback, can hit the same session concurrently. Per-session `ReentrantLock` keeps sessions independent (no global serialization) while making each session's state transitions atomic — same per-resource philosophy as your Parking Lot spots and Concert seats.
2. **Why `LongAdder` for play counts instead of `AtomicLong`?** Play count is the hottest write path in the system (every stream increments it) and reads are rare/approximate. `LongAdder` stripes the counter across cells to eliminate CAS contention; `AtomicLong` would make every concurrent play retry on one cache line. When reads must be exact and frequent, prefer `AtomicLong`.

---

## Step 4 — Implementation (with demo)

> Compilable as a single package. Boilerplate getters trimmed where obvious.

### Enums & Exceptions

```java
public enum SubscriptionTier { FREE, PREMIUM }
public enum PlaybackState { STOPPED, PLAYING, PAUSED }
public enum Genre { POP, ROCK, JAZZ, CLASSICAL, HIPHOP, ELECTRONIC }

public class AuthenticationException extends RuntimeException {
    public AuthenticationException(String m) { super(m); }
}
public class AuthorizationException extends RuntimeException {
    public AuthorizationException(String m) { super(m); }
}
public class NotFoundException extends RuntimeException {
    public NotFoundException(String m) { super(m); }
}
public class PlaybackException extends RuntimeException {
    public PlaybackException(String m) { super(m); }
}
```

### Catalog Entities

```java
import java.util.*;
import java.util.concurrent.atomic.LongAdder;

public class Song {
    private final String id;
    private final String title;
    private final String artistId;
    private final String albumId;
    private final Genre genre;
    private final int durationSec;
    // Hottest write path in the system: every play increments.
    // LongAdder stripes increments across cells -> no CAS contention.
    private final LongAdder playCount = new LongAdder();

    public Song(String id, String title, String artistId, String albumId,
                Genre genre, int durationSec) {
        this.id = id; this.title = title; this.artistId = artistId;
        this.albumId = albumId; this.genre = genre; this.durationSec = durationSec;
    }
    public void recordPlay()      { playCount.increment(); }
    public long getPlayCount()    { return playCount.sum(); }   // approximate under load — fine for "trending"
    public String getId()         { return id; }
    public String getTitle()      { return title; }
    public String getArtistId()   { return artistId; }
    public String getAlbumId()    { return albumId; }
    public Genre getGenre()       { return genre; }
    public int getDurationSec()   { return durationSec; }
    @Override public String toString() { return title + " [" + genre + ", " + durationSec + "s]"; }
}

public class Album {
    private final String id, title, artistId;
    private final int year;
    private final List<String> songIds = Collections.synchronizedList(new ArrayList<>());
    public Album(String id, String title, String artistId, int year) {
        this.id = id; this.title = title; this.artistId = artistId; this.year = year;
    }
    public void addSong(String songId) { songIds.add(songId); }
    public String getId() { return id; }
    public String getTitle() { return title; }
    public List<String> getSongIds() { return List.copyOf(songIds); }
}

public class Artist {
    private final String id, name;
    private final List<String> albumIds = Collections.synchronizedList(new ArrayList<>());
    public Artist(String id, String name) { this.id = id; this.name = name; }
    public void addAlbum(String albumId) { albumIds.add(albumId); }
    public String getId() { return id; }
    public String getName() { return name; }
}
```

### User, History, Playlist

```java
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;

public final class PlayEvent {            // immutable log entry — no identity, no entity class needed
    private final String songId;
    private final Instant at;
    public PlayEvent(String songId, Instant at) { this.songId = songId; this.at = at; }
    public String getSongId() { return songId; }
    public Instant getAt() { return at; }
}

public class ListeningHistory {
    // Append-mostly, read-recent: lock-free deque fits perfectly.
    private final ConcurrentLinkedDeque<PlayEvent> events = new ConcurrentLinkedDeque<>();
    public void record(PlayEvent e) { events.addFirst(e); }
    public List<PlayEvent> recent(int n) {
        return events.stream().limit(n).collect(java.util.stream.Collectors.toList());
    }
}

public class User {
    private final String id, name, email;
    private final String passwordHash;                 // never store raw passwords, even in a toy
    private volatile SubscriptionTier tier;            // volatile: upgrades visible across threads
    private final ListeningHistory history = new ListeningHistory();   // composition
    private final Map<String, Playlist> playlists = new ConcurrentHashMap<>();

    public User(String id, String name, String email, String passwordHash, SubscriptionTier tier) {
        this.id = id; this.name = name; this.email = email;
        this.passwordHash = passwordHash; this.tier = tier;
    }
    public boolean passwordMatches(String hash) { return passwordHash.equals(hash); }
    public String getId() { return id; }
    public String getName() { return name; }
    public String getEmail() { return email; }
    public SubscriptionTier getTier() { return tier; }
    public void setTier(SubscriptionTier t) { this.tier = t; }
    public ListeningHistory getHistory() { return history; }
    public Map<String, Playlist> getPlaylists() { return playlists; }
}

public class Playlist {
    private final String id;
    private volatile String name;
    private final String ownerId;
    // Ordered + reorderable => List, not Set. Mutations guarded by a
    // PER-PLAYLIST lock: edits to different playlists never contend.
    private final List<Song> songs = new ArrayList<>();
    private final ReentrantLock lock = new ReentrantLock();

    public Playlist(String id, String name, String ownerId) {
        this.id = id; this.name = name; this.ownerId = ownerId;
    }

    public void addSong(Song s) {
        lock.lock();
        try { songs.add(Objects.requireNonNull(s)); }
        finally { lock.unlock(); }
    }

    public boolean removeSong(String songId) {
        lock.lock();
        try { return songs.removeIf(s -> s.getId().equals(songId)); }
        finally { lock.unlock(); }
    }

    public void move(int from, int to) {
        lock.lock();
        try {
            if (from < 0 || from >= songs.size() || to < 0 || to >= songs.size())
                throw new IndexOutOfBoundsException("Bad reorder " + from + "->" + to);
            songs.add(to, songs.remove(from));
        } finally { lock.unlock(); }
    }

    /** Defensive snapshot: callers iterate safely while edits continue. */
    public List<Song> snapshot() {
        lock.lock();
        try { return List.copyOf(songs); }
        finally { lock.unlock(); }
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public void rename(String n) { this.name = n; }
    public String getOwnerId() { return ownerId; }
}
```

### Tier Policy (Strategy)

```java
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public interface PlaybackRules {
    /** May this session skip right now? Implementations may mutate counters. */
    boolean tryConsumeSkip();
    /** Hook before a song starts (ads, etc.). Returns optional message. */
    String onSongStart(Song s);
}

public class PremiumTierRules implements PlaybackRules {
    @Override public boolean tryConsumeSkip() { return true; }
    @Override public String onSongStart(Song s) { return null; }   // no ads
}

public class FreeTierRules implements PlaybackRules {
    private static final int MAX_SKIPS_PER_HOUR = 6;
    private static final int AD_EVERY_N_SONGS = 3;
    private final AtomicInteger skipsInWindow = new AtomicInteger();
    private final AtomicLong windowStartMs = new AtomicLong(System.currentTimeMillis());
    private final AtomicInteger songsSinceAd = new AtomicInteger();

    @Override
    public boolean tryConsumeSkip() {
        long now = System.currentTimeMillis();
        long start = windowStartMs.get();
        if (now - start > 3_600_000 && windowStartMs.compareAndSet(start, now)) {
            skipsInWindow.set(0);          // window rolled; CAS ensures only one thread resets
        }
        // Optimistic increment-then-check keeps this lock-free.
        return skipsInWindow.incrementAndGet() <= MAX_SKIPS_PER_HOUR;
    }

    @Override
    public String onSongStart(Song s) {
        if (songsSinceAd.incrementAndGet() >= AD_EVERY_N_SONGS) {
            songsSinceAd.set(0);
            return ">> [AD] Upgrade to Premium for uninterrupted music! <<";
        }
        return null;
    }
}
```

### Playback Session (the heart of the design)

```java
import java.time.Instant;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;

public class PlaybackSession {
    private final User user;
    private final PlaybackRules rules;
    private final List<PlaybackListener> listeners;       // shared CopyOnWriteArrayList from service

    private final Deque<Song> queue = new ArrayDeque<>(); // guarded by lock
    private Song currentSong;                             // guarded by lock
    private PlaybackState state = PlaybackState.STOPPED;  // guarded by lock
    private int positionSec;                              // guarded by lock

    // Per-session lock: one user's web + mobile clients (or a track-finished
    // callback racing a UI action) serialize HERE, not across all users.
    private final ReentrantLock lock = new ReentrantLock();

    public PlaybackSession(User user, PlaybackRules rules, List<PlaybackListener> listeners) {
        this.user = user; this.rules = rules; this.listeners = listeners;
    }

    public void enqueue(Collection<Song> songs) {
        lock.lock();
        try { queue.addAll(songs); }
        finally { lock.unlock(); }
    }

    public void play(Song song) {
        Objects.requireNonNull(song, "song");
        String ad;
        lock.lock();
        try {
            currentSong = song;
            positionSec = 0;
            state = PlaybackState.PLAYING;
            ad = rules.onSongStart(song);
        } finally { lock.unlock(); }
        if (ad != null) System.out.println(ad);
        // Notify OUTSIDE the lock: listeners are user-supplied code and may be
        // slow — never hold a lock across code you don't control (Airline lesson,
        // same reason locks never span payment-gateway calls).
        PlayEvent event = new PlayEvent(song.getId(), Instant.now());
        for (PlaybackListener l : listeners) l.onSongPlayed(user, song, event);
        System.out.printf("[%s] ▶ Playing: %s%n", user.getName(), song);
    }

    public void pause() {
        lock.lock();
        try {
            if (state != PlaybackState.PLAYING)
                throw new PlaybackException("Cannot pause from " + state);   // enum-guarded transition
            state = PlaybackState.PAUSED;
            System.out.printf("[%s] ⏸ Paused at %ds%n", user.getName(), positionSec);
        } finally { lock.unlock(); }
    }

    public void resume() {
        lock.lock();
        try {
            if (state != PlaybackState.PAUSED)
                throw new PlaybackException("Cannot resume from " + state);
            state = PlaybackState.PLAYING;
            System.out.printf("[%s] ▶ Resumed at %ds%n", user.getName(), positionSec);
        } finally { lock.unlock(); }
    }

    public void seek(int seconds) {
        lock.lock();
        try {
            if (currentSong == null) throw new PlaybackException("Nothing playing");
            // Clamp, don't throw: seeking past the end is a UX no-op, not an error.
            positionSec = Math.max(0, Math.min(seconds, currentSong.getDurationSec()));
            System.out.printf("[%s] ⏩ Seek to %ds in %s%n",
                    user.getName(), positionSec, currentSong.getTitle());
        } finally { lock.unlock(); }
    }

    public void skip() {
        Song next;
        lock.lock();
        try {
            if (!rules.tryConsumeSkip())
                throw new PlaybackException("Skip limit reached for FREE tier — upgrade to Premium");
            next = queue.poll();
            if (next == null) {
                state = PlaybackState.STOPPED;
                currentSong = null;
                System.out.printf("[%s] ⏭ Queue empty — stopped%n", user.getName());
                return;
            }
        } finally { lock.unlock(); }
        play(next);   // play() re-acquires; ReentrantLock would even tolerate nesting
    }

    public PlaybackState getState() { lock.lock(); try { return state; } finally { lock.unlock(); } }
}
```

### Observer: play-event consumers

```java
public interface PlaybackListener {
    void onSongPlayed(User user, Song song, PlayEvent event);
}

public class HistoryRecorder implements PlaybackListener {
    @Override public void onSongPlayed(User u, Song s, PlayEvent e) { u.getHistory().record(e); }
}

public class PlayCountUpdater implements PlaybackListener {
    @Override public void onSongPlayed(User u, Song s, PlayEvent e) { s.recordPlay(); }
}
```

### Recommendations (Strategy)

```java
import java.util.*;
import java.util.stream.Collectors;

public interface RecommendationStrategy {
    List<Song> recommend(User user, MusicLibrary library, int limit);
}

/** Most-played genres in the user's recent history → unheard songs of those genres. */
public class GenreAffinityStrategy implements RecommendationStrategy {
    @Override
    public List<Song> recommend(User user, MusicLibrary library, int limit) {
        List<PlayEvent> recent = user.getHistory().recent(50);
        if (recent.isEmpty()) return new TrendingStrategy().recommend(user, library, limit);

        Set<String> heard = recent.stream().map(PlayEvent::getSongId).collect(Collectors.toSet());
        Map<Genre, Long> affinity = recent.stream()
                .map(e -> library.getSong(e.getSongId()))
                .filter(Objects::nonNull)
                .collect(Collectors.groupingBy(Song::getGenre, Collectors.counting()));

        return library.allSongs().stream()
                .filter(s -> !heard.contains(s.getId()))
                .sorted(Comparator.comparingLong(
                        (Song s) -> affinity.getOrDefault(s.getGenre(), 0L)).reversed())
                .limit(limit)
                .collect(Collectors.toList());
    }
}

/** Global popularity by play count — also the cold-start fallback. */
public class TrendingStrategy implements RecommendationStrategy {
    @Override
    public List<Song> recommend(User user, MusicLibrary library, int limit) {
        return library.allSongs().stream()
                .sorted(Comparator.comparingLong(Song::getPlayCount).reversed())
                .limit(limit)
                .collect(Collectors.toList());
    }
}
```

### Library & Auth

```java
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class MusicLibrary {
    private final Map<String, Song> songs = new ConcurrentHashMap<>();
    private final Map<String, Album> albums = new ConcurrentHashMap<>();
    private final Map<String, Artist> artists = new ConcurrentHashMap<>();

    public void addArtist(Artist a) { artists.put(a.getId(), a); }
    public void addAlbum(Album al)  { albums.put(al.getId(), al);
                                      artists.get(al.getId()) /* no-op guard */ ;
    }
    public void addAlbum(Album al, Artist owner) {
        albums.put(al.getId(), al); owner.addAlbum(al.getId());
    }
    public void addSong(Song s, Album album) {
        songs.put(s.getId(), s); album.addSong(s.getId());
    }

    public Song getSong(String id) { return songs.get(id); }
    public Collection<Song> allSongs() { return Collections.unmodifiableCollection(songs.values()); }

    // Linear scan is O(n) — acceptable here, and isolated behind this class so a
    // trie / inverted index / Elasticsearch can replace it without touching callers.
    public List<Song> searchSongs(String q) {
        String needle = q.toLowerCase(Locale.ROOT);
        return songs.values().stream()
                .filter(s -> s.getTitle().toLowerCase(Locale.ROOT).contains(needle))
                .collect(Collectors.toList());
    }
    public List<Artist> searchArtists(String q) {
        String needle = q.toLowerCase(Locale.ROOT);
        return artists.values().stream()
                .filter(a -> a.getName().toLowerCase(Locale.ROOT).contains(needle))
                .collect(Collectors.toList());
    }
}

public class UserManager {
    private final Map<String, User> usersByEmail = new ConcurrentHashMap<>();
    private final Map<String, User> sessions = new ConcurrentHashMap<>();   // token -> user

    public User register(String name, String email, String password, SubscriptionTier tier) {
        User u = new User(UUID.randomUUID().toString(), name, email, hash(password), tier);
        // putIfAbsent = atomic check-and-set: two concurrent registrations of the
        // same email cannot both succeed (Digital Wallet account-creation lesson).
        if (usersByEmail.putIfAbsent(email.toLowerCase(Locale.ROOT), u) != null)
            throw new AuthenticationException("Email already registered: " + email);
        return u;
    }

    public String login(String email, String password) {
        User u = usersByEmail.get(email.toLowerCase(Locale.ROOT));
        if (u == null || !u.passwordMatches(hash(password)))
            throw new AuthenticationException("Invalid credentials");
        String token = UUID.randomUUID().toString();
        sessions.put(token, u);
        return token;
    }

    public User authenticate(String token) {
        User u = sessions.get(token);
        if (u == null) throw new AuthenticationException("Invalid or expired token");
        return u;
    }

    private String hash(String pw) { return Integer.toHexString(pw.hashCode()); } // stand-in for BCrypt
}
```

### Service Facade (Singleton)

```java
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public class MusicStreamingService {
    // Initialization-on-demand holder: lazy, thread-safe, no synchronization cost.
    private static class Holder { static final MusicStreamingService INSTANCE = new MusicStreamingService(); }
    public static MusicStreamingService getInstance() { return Holder.INSTANCE; }

    private final MusicLibrary library = new MusicLibrary();
    private final UserManager userManager = new UserManager();
    private final Map<String, PlaybackSession> sessions = new ConcurrentHashMap<>(); // userId -> session
    // Listener registration is rare, iteration is constant → CopyOnWriteArrayList.
    private final List<PlaybackListener> listeners = new CopyOnWriteArrayList<>();
    private volatile RecommendationStrategy recommender = new GenreAffinityStrategy(); // hot-swappable

    private MusicStreamingService() {
        listeners.add(new HistoryRecorder());
        listeners.add(new PlayCountUpdater());
    }

    public MusicLibrary library() { return library; }
    public UserManager users() { return userManager; }
    public void setRecommender(RecommendationStrategy r) { this.recommender = r; }
    public void addListener(PlaybackListener l) { listeners.add(l); }

    private PlaybackSession sessionFor(User u) {
        // computeIfAbsent = atomic get-or-create: two devices logging in
        // concurrently still converge on ONE session object.
        return sessions.computeIfAbsent(u.getId(), id -> {
            PlaybackRules rules = (u.getTier() == SubscriptionTier.PREMIUM)
                    ? new PremiumTierRules() : new FreeTierRules();
            return new PlaybackSession(u, rules, listeners);
        });
    }

    // ---- Playback API ----
    public void play(String token, String songId) {
        User u = userManager.authenticate(token);
        Song s = library.getSong(songId);
        if (s == null) throw new NotFoundException("No song " + songId);
        sessionFor(u).play(s);
    }
    public void pause(String token)  { sessionFor(userManager.authenticate(token)).pause(); }
    public void resume(String token) { sessionFor(userManager.authenticate(token)).resume(); }
    public void skip(String token)   { sessionFor(userManager.authenticate(token)).skip(); }
    public void seek(String token, int sec) { sessionFor(userManager.authenticate(token)).seek(sec); }

    public void playPlaylist(String token, String playlistId) {
        User u = userManager.authenticate(token);
        Playlist p = u.getPlaylists().get(playlistId);
        if (p == null) throw new NotFoundException("No playlist " + playlistId);
        List<Song> snap = p.snapshot();
        if (snap.isEmpty()) throw new PlaybackException("Playlist is empty");
        PlaybackSession session = sessionFor(u);
        session.enqueue(snap.subList(1, snap.size()));
        session.play(snap.get(0));
    }

    // ---- Playlist API (authorization enforced here) ----
    public Playlist createPlaylist(String token, String name) {
        User u = userManager.authenticate(token);
        Playlist p = new Playlist(UUID.randomUUID().toString(), name, u.getId());
        u.getPlaylists().put(p.getId(), p);
        return p;
    }

    public void addToPlaylist(String token, String playlistId, String songId) {
        User u = userManager.authenticate(token);
        Playlist p = u.getPlaylists().get(playlistId);
        if (p == null) throw new NotFoundException("No playlist " + playlistId);
        if (!p.getOwnerId().equals(u.getId()))
            throw new AuthorizationException("Not your playlist");   // authn ≠ authz
        Song s = library.getSong(songId);
        if (s == null) throw new NotFoundException("No song " + songId);
        p.addSong(s);
    }

    // ---- Discovery ----
    public List<Song> search(String q) { return library.searchSongs(q); }
    public List<Song> recommend(String token, int n) {
        return recommender.recommend(userManager.authenticate(token), library, n);
    }
}
```

### Demo

```java
public class MusicStreamingDemo {
    public static void main(String[] args) {
        MusicStreamingService svc = MusicStreamingService.getInstance();

        // --- Seed catalog ---
        Artist queen = new Artist("ar1", "Queen");
        svc.library().addArtist(queen);
        Album nightOpera = new Album("al1", "A Night at the Opera", "ar1", 1975);
        svc.library().addAlbum(nightOpera, queen);
        Song s1 = new Song("s1", "Bohemian Rhapsody", "ar1", "al1", Genre.ROCK, 354);
        Song s2 = new Song("s2", "Love of My Life",   "ar1", "al1", Genre.ROCK, 217);
        Song s3 = new Song("s3", "Smooth Jazz Nights","ar2", "al2", Genre.JAZZ, 240);
        Song s4 = new Song("s4", "Midnight Sax",      "ar2", "al2", Genre.JAZZ, 198);
        svc.library().addSong(s1, nightOpera);
        svc.library().addSong(s2, nightOpera);
        svc.library().addSong(s3, nightOpera);   // demo shortcut
        svc.library().addSong(s4, nightOpera);

        // --- Register & login ---
        svc.users().register("Anbu", "anbu@mail.com", "secret", SubscriptionTier.FREE);
        String token = svc.users().login("anbu@mail.com", "secret");

        // --- Search ---
        System.out.println("Search 'rhap': " + svc.search("rhap"));

        // --- Playlist ---
        Playlist road = svc.createPlaylist(token, "Road Trip");
        svc.addToPlaylist(token, road.getId(), "s1");
        svc.addToPlaylist(token, road.getId(), "s2");
        svc.addToPlaylist(token, road.getId(), "s3");

        // --- Playback ---
        svc.playPlaylist(token, road.getId());   // plays s1, queues s2,s3
        svc.seek(token, 120);
        svc.pause(token);
        svc.resume(token);
        svc.skip(token);                         // -> s2 (FREE: skip counted, ad cadence ticking)
        svc.skip(token);                         // -> s3 (an ad should appear by now)

        // --- Recommendations (history is all ROCK+JAZZ; expect unheard s4 JAZZ high) ---
        System.out.println("Recommended: " + svc.recommend(token, 3));

        // --- Edge: invalid transition ---
        try { svc.resume(token); }               // already PLAYING
        catch (PlaybackException e) { System.out.println("Expected: " + e.getMessage()); }
    }
}
```

---

## Step 5 — Exception Handling, Edge Cases, Concurrency

### Exception Strategy

| Exception | Thrown when | Why a distinct type |
|-----------|-------------|---------------------|
| `AuthenticationException` | Bad credentials, invalid/expired token, duplicate email | Maps to HTTP 401 in a real API |
| `AuthorizationException` | Valid user touching another user's playlist | 403 — *authn succeeded, authz failed*; interviewers love this distinction |
| `NotFoundException` | Unknown song/playlist ID | 404; fail fast at the facade, before any state mutates |
| `PlaybackException` | Illegal state transition, empty queue/playlist, skip limit | Domain rule violations, recoverable by the client |

Validation lives at the **facade boundary** (`MusicStreamingService`), so inner classes (`PlaybackSession`, `Playlist`) can assume non-null, authorized inputs — defense in depth without duplicated checks everywhere.

### Edge Cases

1. **Seek beyond duration / negative seek** → clamp to `[0, duration]`, don't throw. Seeking is a UX gesture, not a contract violation.
2. **Skip with empty queue** → transition to `STOPPED` cleanly; never NPE on `currentSong`.
3. **Pause when not playing / resume when not paused** → `PlaybackException` via enum-guarded transitions — the lightweight State pattern.
4. **Duplicate registration race** → `putIfAbsent` keyed by lowercased email; exactly one wins, the loser gets a clean exception.
5. **Playlist reorder with stale indexes** → bounds-checked inside the lock; the check and the move are atomic together.
6. **Empty listening history (cold start)** → `GenreAffinityStrategy` falls back to `TrendingStrategy` rather than returning nothing.
7. **Free-tier window rollover race** → CAS on `windowStartMs` ensures exactly one thread resets the skip counter; others observe the reset.
8. **Recommending songs the user just heard** → recent-history exclusion set; an interviewer may push on "how recent" (time-decay weighting is the natural extension).

### Concurrency Analysis

**Shared mutable state inventory:**

| State | Writers | Primitive | Why this one |
|-------|---------|-----------|--------------|
| Catalog/user/session registries | Admin adds, user signups, session creation | `ConcurrentHashMap` (+ `putIfAbsent` / `computeIfAbsent`) | Atomic check-and-set without external locks |
| `Song.playCount` | Every play, every user | `LongAdder` | Write-hot, read-approximate → striped counter beats `AtomicLong` CAS storms |
| `Playlist.songs` | Owner's devices | Per-playlist `ReentrantLock` | Reorder = multi-step (remove+insert) → needs a real critical section; per-resource so playlists don't contend with each other |
| `PlaybackSession` (state, position, queue, current) | User's devices + internal callbacks | Per-session `ReentrantLock` | State transition + queue poll must be atomic *together*; per-session keeps users fully parallel |
| Listener list | Rare registration, constant iteration | `CopyOnWriteArrayList` | Iteration never blocks or throws CME; perfect read-heavy ratio |
| `User.tier`, `recommender` ref | Occasional swap | `volatile` | Single-reference visibility; no compound operation → no lock needed |
| `ListeningHistory` | Appends per play | `ConcurrentLinkedDeque` | Lock-free append-mostly log |

**Critical sections:** (a) playback state transition + queue mutation in `PlaybackSession`; (b) playlist mutate/reorder/snapshot; (c) skip-window reset in `FreeTierRules`. Everything else is lock-free.

**Deadlock argument:** No code path ever holds two locks simultaneously — `skip()` releases the session lock *before* calling `play()` (and even nested re-acquisition would be safe under `ReentrantLock` reentrancy); listener notification happens strictly outside the session lock; playlist `snapshot()` is taken before `enqueue()` acquires the session lock. **One-lock-at-a-time ⇒ no cycle in the lock graph ⇒ no deadlock.** This is a stronger claim than lock-ordering and is the cleanest argument you can make in an interview.

**Race-freedom highlights:** every read-modify-write on shared state is either inside its owning lock, an atomic map operation (`putIfAbsent`/`computeIfAbsent`), or a CAS (`windowStartMs`). Listener callbacks receive immutable `PlayEvent`s, so fan-out adds no races.

**Livelock/starvation:** locks are uncontended in the common case (each guards one user's resource); `ReentrantLock` default unfairness is acceptable since hold times are microseconds.

**Why not `ReadWriteLock` on playlists?** Reads (`snapshot`) are cheap and rare relative to the complexity cost; and your LRU Cache lesson applies in spirit — if "read" paths ever mutate (e.g., auto-cleanup of deleted songs on read), `ReadWriteLock` becomes a trap.

---

## Interviewer Follow-Ups (with model answers)

**1. "Make playlists collaborative — multiple users editing the same playlist."**
Mechanically, the design is already 90% there: the per-playlist `ReentrantLock` doesn't care who's calling. Changes: move playlists out of `User` into a `PlaylistRepository` keyed by ID; replace the single-owner check with an ACL (`Set<String> collaboratorIds`, itself a concurrent set). The new problem is *semantic conflict* (two users reorder simultaneously — both succeed, result surprises one of them): mention optimistic versioning (`version` field, reject stale edits) as the next step, which is exactly the OCC pattern from your Digital Wallet discussion.

**2. "Support multi-device playback with handoff (Spotify Connect)."**
Keep one *logical* session per user but add a `deviceId` for the active sink. Handoff = atomic swap of the active device inside the session lock, carrying `positionSec` over. The session-per-user `computeIfAbsent` already guarantees both devices converge on the same session object — that decision pays off here.

**3. "10x scale — what breaks first?"**
(a) Linear-scan search → inverted index / trie, or externalize to Elasticsearch; the `MusicLibrary` seam means callers don't change. (b) Single-JVM singleton → the facade becomes a stateless service tier; sessions move to Redis keyed by userId; per-resource locks become distributed locks or, better, single-writer partitioning by userId (each user's session is owned by one shard — locks become local again). (c) Play counts → per-node `LongAdder`s flushed to a stream (Kafka) and aggregated; "trending" tolerates approximation by design.

**4. "Add offline playback."**
A `DownloadManager` with a `DownloadStatus` enum lifecycle (REQUESTED → DOWNLOADING → AVAILABLE → EXPIRED) and license checks against tier — Strategy again (`PremiumTierRules` allows downloads). Playback then resolves the audio source through a `SongSource` abstraction (STREAM vs LOCAL) — a textbook place to *introduce* an interface only when the second implementation arrives.

**5. "Songs by multiple artists; one song on multiple albums."**
Because cross-aggregate references are already by-ID, this is `String artistId` → `List<String> artistIds` plus a join-style lookup — no object-graph surgery. Call out that this validates the IDs-over-references decision.

---

## Transferable Lessons

1. **Per-resource locking, third domain in a row:** parking spots → seats → *playback sessions and playlists*. The pattern is identical; what changed is recognizing *what the resource is*. In interviews, finding the right lock granularity **is** the concurrency answer.
2. **Notify outside the lock:** Observer fan-out, like payment-gateway calls in Airline, is code you don't control. Locks guard *state*, never *foreign code*.
3. **`LongAdder` vs `AtomicLong`:** new primitive in your toolkit — choose by write-contention and read-exactness, and say those two words in the interview.
4. **Policy vs mechanism via Strategy:** tier rules and recommendations are policies injected into a stable mechanism (`PlaybackSession`, facade). Any "FREE vs PREMIUM"-shaped requirement should trigger this reflex.
5. **IDs across aggregate boundaries** future-proofs the model (follow-up #5) and mirrors sharding at scale — a small choice with an outsized payoff under probing.

**Suggested next problem:** **Movie Ticket Booking System** — it recombines this problem's session/auth structure with Concert Ticket's multi-seat atomicity, escalating to *multi-resource transactions with seat-hold TTLs*, which is the single most-asked Hard LLD in interviews.
