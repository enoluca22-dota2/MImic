// MImic additions to the Listen Together server (GPL-3.0): endParty, voteSkip,
// and the access rules around them.

package main

import (
	"bytes"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"net/url"
	"testing"
	"time"

	"github.com/gorilla/websocket"

	"mimic/party/protocol"
)

type member struct {
	code, token string
	ws          *websocket.Conn
}

func createOrJoin(t *testing.T, ts *httptest.Server, path, user string) (string, string) {
	t.Helper()
	b, _ := json.Marshal(map[string]string{"userId": user, "deviceId": "dev-" + user, "displayName": user})
	res, err := http.Post(ts.URL+path, "application/json", bytes.NewReader(b))
	if err != nil {
		t.Fatalf("POST %s: %v", path, err)
	}
	defer res.Body.Close()
	if res.StatusCode != http.StatusCreated && res.StatusCode != http.StatusOK {
		t.Fatalf("POST %s: status %d", path, res.StatusCode)
	}
	var r map[string]interface{}
	_ = json.NewDecoder(res.Body).Decode(&r)
	return r["code"].(string), r["token"].(string)
}

func dial(t *testing.T, ts *httptest.Server, code, token string) *websocket.Conn {
	t.Helper()
	u, _ := url.Parse(ts.URL)
	u.Scheme = "ws"
	u.Path = fmt.Sprintf("/ws/parties/%s", code)
	h := http.Header{}
	h.Set("Authorization", "Bearer "+token)
	ws, _, err := websocket.DefaultDialer.Dial(u.String(), h)
	if err != nil {
		t.Fatalf("dial: %v", err)
	}
	return ws
}

// next reads frames until one of type kind arrives (or fails after a few seconds).
func next(t *testing.T, ws *websocket.Conn, kind string) map[string]interface{} {
	t.Helper()
	deadline := time.Now().Add(3 * time.Second)
	for time.Now().Before(deadline) {
		_ = ws.SetReadDeadline(deadline)
		var f map[string]interface{}
		if err := ws.ReadJSON(&f); err != nil {
			t.Fatalf("waiting for %s: %v", kind, err)
		}
		if f["type"] == kind {
			return f
		}
	}
	t.Fatalf("no %s frame", kind)
	return nil
}

func control(t *testing.T, ws *websocket.Conn, action string, extra map[string]interface{}) {
	t.Helper()
	f := map[string]interface{}{"type": protocol.FrameControl, "action": action}
	for k, v := range extra {
		f[k] = v
	}
	if err := ws.WriteJSON(f); err != nil {
		t.Fatalf("send %s: %v", action, err)
	}
}

func party3(t *testing.T, ts *httptest.Server) (host, a, b member) {
	code, ht := createOrJoin(t, ts, "/api/parties", "host")
	_, at := createOrJoin(t, ts, "/api/parties/"+code+"/join", "alice")
	_, bt := createOrJoin(t, ts, "/api/parties/"+code+"/join", "bob")
	host = member{code, ht, dial(t, ts, code, ht)}
	a = member{code, at, dial(t, ts, code, at)}
	b = member{code, bt, dial(t, ts, code, bt)}
	for _, m := range []member{host, a, b} {
		next(t, m.ws, protocol.FrameWelcome)
	}
	return
}

func TestVoteSkipNeedsAMajority(t *testing.T) {
	ts := setupTestServer()
	defer ts.Close()
	host, a, b := party3(t, ts)
	defer host.ws.Close()
	defer a.ws.Close()
	defer b.ws.Close()

	q := []interface{}{
		map[string]interface{}{"videoId": "song1", "title": "One"},
		map[string]interface{}{"videoId": "song2", "title": "Two"},
	}
	control(t, host.ws, protocol.ActionSetQueue, map[string]interface{}{"queue": q, "queueIndex": 0})
	control(t, host.ws, protocol.ActionSetTrack, map[string]interface{}{"track": q[0], "positionMs": 0, "isPlaying": true, "queueIndex": 0})
	for {
		st := next(t, a.ws, protocol.FrameState)
		if tr, _ := st["playback"].(map[string]interface{})["track"].(map[string]interface{}); tr != nil && tr["videoId"] == "song1" {
			break
		}
	}

	// One of three connected devices: 1 vote, 2 needed, still song1.
	control(t, a.ws, protocol.ActionVoteSkip, nil)
	st := next(t, a.ws, protocol.FrameState)["playback"].(map[string]interface{})
	if st["skipVotes"] != float64(1) || st["skipVotesNeeded"] != float64(2) {
		t.Fatalf("after one vote: votes=%v needed=%v", st["skipVotes"], st["skipVotesNeeded"])
	}
	if st["track"].(map[string]interface{})["videoId"] != "song1" {
		t.Fatalf("one vote must not skip")
	}
	// The same member voting again changes nothing.
	control(t, a.ws, protocol.ActionVoteSkip, nil)
	st = next(t, a.ws, protocol.FrameState)["playback"].(map[string]interface{})
	if st["skipVotes"] != float64(1) {
		t.Fatalf("a repeated vote counted twice: %v", st["skipVotes"])
	}
	// A second member: majority, skipped to song2, votes reset.
	control(t, b.ws, protocol.ActionVoteSkip, nil)
	st = next(t, a.ws, protocol.FrameState)["playback"].(map[string]interface{})
	if st["track"].(map[string]interface{})["videoId"] != "song2" {
		t.Fatalf("majority vote did not skip: %v", st["track"])
	}
	if st["skipVotes"] != float64(0) {
		t.Fatalf("votes not reset after the skip: %v", st["skipVotes"])
	}
}

func TestVoteSkipWorksInHostOnlyParty(t *testing.T) {
	ts := setupTestServer()
	defer ts.Close()
	host, a, b := party3(t, ts)
	defer host.ws.Close()
	defer a.ws.Close()
	defer b.ws.Close()
	control(t, host.ws, protocol.ActionSetTrack, map[string]interface{}{"track": map[string]interface{}{"videoId": "s"}, "isPlaying": true})
	control(t, host.ws, protocol.ActionSetHostOnlyControl, map[string]interface{}{"enabled": true})
	next(t, a.ws, protocol.FrameMembers)

	// A listener may not pause a host-only party...
	control(t, a.ws, protocol.ActionPause, nil)
	e := next(t, a.ws, protocol.FrameError)
	if e["error"] != "host_only" {
		t.Fatalf("listener pause in host-only party: %v", e["error"])
	}
	// ...but may still vote.
	control(t, a.ws, protocol.ActionVoteSkip, nil)
	for {
		st := next(t, a.ws, protocol.FrameState)["playback"].(map[string]interface{})
		if st["skipVotes"] == float64(1) {
			break
		}
	}
}

func TestOnlyTheHostEndsTheParty(t *testing.T) {
	ts := setupTestServer()
	defer ts.Close()
	host, a, b := party3(t, ts)
	defer host.ws.Close()
	defer a.ws.Close()
	defer b.ws.Close()

	control(t, a.ws, protocol.ActionEndParty, nil)
	if e := next(t, a.ws, protocol.FrameError); e["error"] != "host_only" {
		t.Fatalf("listener endParty: %v", e["error"])
	}

	control(t, host.ws, protocol.ActionEndParty, nil)
	for _, m := range []member{a, b} {
		if bye := next(t, m.ws, protocol.FrameBye); bye["reason"] != "ended" {
			t.Fatalf("bye reason: %v", bye["reason"])
		}
	}
	// The party is gone: joining again is a 404, and the old token opens nothing.
	bd, _ := json.Marshal(map[string]string{"userId": "x", "deviceId": "dx", "displayName": "x"})
	res, _ := http.Post(ts.URL+"/api/parties/"+host.code+"/join", "application/json", bytes.NewReader(bd))
	if res.StatusCode != http.StatusNotFound {
		t.Fatalf("join after end: %d", res.StatusCode)
	}
}

func TestWrongTokenIsRejected(t *testing.T) {
	ts := setupTestServer()
	defer ts.Close()
	code, _ := createOrJoin(t, ts, "/api/parties", "host")
	u, _ := url.Parse(ts.URL)
	u.Scheme = "ws"
	u.Path = "/ws/parties/" + code
	h := http.Header{}
	h.Set("Authorization", "Bearer not-a-real-token")
	_, res, err := websocket.DefaultDialer.Dial(u.String(), h)
	if err == nil {
		t.Fatalf("a wrong token opened a party socket")
	}
	if res == nil || res.StatusCode != http.StatusUnauthorized {
		t.Fatalf("expected 401, got %v", res)
	}
	req, _ := http.NewRequest("GET", ts.URL+"/api/parties/"+code, nil)
	req.Header.Set("Authorization", "Bearer not-a-real-token")
	r2, _ := http.DefaultClient.Do(req)
	if r2.StatusCode != http.StatusUnauthorized {
		t.Fatalf("snapshot with a wrong token: %d", r2.StatusCode)
	}
}
