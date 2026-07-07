// Copyright 2018 Google LLC
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.firebase.firestore.core;

import static com.google.firebase.firestore.testutil.TestUtil.path;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.firebase.firestore.FirebaseFirestoreException;
import com.google.firebase.firestore.core.EventManager.ListenOptions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InOrder;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** Tests EventManager and QueryEventCallbackImpl */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE)
public class EventManagerTest {
  private static QueryListener queryListener(Query query) {
    return new QueryListener(
        new QueryOrPipeline.QueryWrapper(query), new ListenOptions(), (value, error) -> {});
  }

  private static QueryListener queryListener(Query query, boolean changesOnly) {
    ListenOptions options = new ListenOptions();
    options.changesOnly = changesOnly;
    return new QueryListener(
        new QueryOrPipeline.QueryWrapper(query), options, (value, error) -> {});
  }

  @Test
  public void testMultipleListensPerQuery() {
    Query query = Query.atPath(path("foo/bar"));
    QueryOrPipeline qop = new QueryOrPipeline.QueryWrapper(query);

    QueryListener listener1 = queryListener(query);
    QueryListener listener2 = queryListener(query);

    SyncEngine syncSpy = mock(SyncEngine.class);

    EventManager manager = new EventManager(syncSpy);
    manager.addQueryListener(listener1);
    manager.addQueryListener(listener2);

    manager.removeQueryListener(listener1);
    manager.removeQueryListener(listener2);
    verify(syncSpy, times(1))
        .listen(
            eq(qop),
            /** shouldListenToRemote= */
            eq(true),
            /** changesOnly= */
            eq(false));
    verify(syncSpy, times(1))
        .stopListening(
            qop,
            /** shouldUnlistenToRemote= */
            true);
  }

  @Test
  public void testChangesOnlyFlagPropagatesToSyncEngineListen() {
    Query query = Query.atPath(path("foo/bar"));
    QueryOrPipeline qop = new QueryOrPipeline.QueryWrapper(query);
    SyncEngine syncSpy = mock(SyncEngine.class);
    EventManager manager = new EventManager(syncSpy);

    manager.addQueryListener(queryListener(query, /* changesOnly= */ true));

    // The changesOnly bit must reach SyncEngine.listen — pinned with eq(true) so a dropped or
    // inverted flag fails the test (previously this was matched with anyBoolean()).
    verify(syncSpy, times(1))
        .listen(
            eq(qop),
            /** shouldListenToRemote= */
            eq(true),
            /** changesOnly= */
            eq(true));
  }

  @Test
  public void testMismatchedChangesOnlyListenerOnSameQueryIsRejected() {
    // A query's view mode (changesOnly) is fixed by the first listener. A later listener that
    // disagrees can't be satisfied by the shared view — a full-data joiner would get field-stripped
    // documents (data loss) and a changes-only joiner would silently retain full data (defeating
    // the projection -> OOM). Both directions are rejected via onError(FAILED_PRECONDITION), which
    // is worker-safe (a throw would panic the AsyncQueue).
    SyncEngine syncSpy = mock(SyncEngine.class);
    EventManager manager = new EventManager(syncSpy);

    // Direction 1: changes-only view exists, a full-data listener joins -> rejected.
    Query q1 = Query.atPath(path("foo/bar"));
    manager.addQueryListener(queryListener(q1, /* changesOnly= */ true));
    FirebaseFirestoreException[] fullJoinerError = new FirebaseFirestoreException[1];
    ListenOptions fullOptions = new ListenOptions();
    fullOptions.changesOnly = false;
    manager.addQueryListener(
        new QueryListener(
            new QueryOrPipeline.QueryWrapper(q1),
            fullOptions,
            (value, error) -> fullJoinerError[0] = error));
    assertNotNull(fullJoinerError[0]);
    assertEquals(FirebaseFirestoreException.Code.FAILED_PRECONDITION, fullJoinerError[0].getCode());

    // Direction 2: full view exists, a changes-only listener joins -> also rejected (OOM guard).
    Query q2 = Query.atPath(path("baz/qux"));
    manager.addQueryListener(queryListener(q2, /* changesOnly= */ false));
    FirebaseFirestoreException[] changesOnlyJoinerError = new FirebaseFirestoreException[1];
    ListenOptions changesOnlyOptions = new ListenOptions();
    changesOnlyOptions.changesOnly = true;
    manager.addQueryListener(
        new QueryListener(
            new QueryOrPipeline.QueryWrapper(q2),
            changesOnlyOptions,
            (value, error) -> changesOnlyJoinerError[0] = error));
    assertNotNull(changesOnlyJoinerError[0]);
    assertEquals(
        FirebaseFirestoreException.Code.FAILED_PRECONDITION, changesOnlyJoinerError[0].getCode());
  }

  @Test
  public void testUnlistensOnUnknownListeners() {
    Query query = Query.atPath(path("foo/bar"));
    QueryOrPipeline qop = new QueryOrPipeline.QueryWrapper(query);

    SyncEngine syncSpy = mock(SyncEngine.class);

    EventManager manager = new EventManager(syncSpy);
    manager.removeQueryListener(queryListener(query));
    verify(syncSpy, never()).stopListening(eq(qop), anyBoolean());
  }

  @Test
  public void testListenCalledInOrder() {
    Query query1 = Query.atPath(path("foo/bar"));
    QueryOrPipeline qop1 = new QueryOrPipeline.QueryWrapper(query1);
    Query query2 = Query.atPath(path("bar/baz"));
    QueryOrPipeline qop2 = new QueryOrPipeline.QueryWrapper(query2);

    SyncEngine syncSpy = mock(SyncEngine.class);
    EventManager eventManager = new EventManager(syncSpy);

    QueryListener spy1 = mock(QueryListener.class);
    when(spy1.getQuery()).thenReturn(qop1);
    QueryListener spy2 = mock(QueryListener.class);
    when(spy2.getQuery()).thenReturn(qop2);
    QueryListener spy3 = mock(QueryListener.class);
    when(spy3.getQuery()).thenReturn(qop1);
    eventManager.addQueryListener(spy1);
    eventManager.addQueryListener(spy2);
    eventManager.addQueryListener(spy3);

    verify(syncSpy, times(1)).listen(eq(qop1), anyBoolean(), anyBoolean());
    verify(syncSpy, times(1)).listen(eq(qop2), anyBoolean(), anyBoolean());

    ViewSnapshot snap1 = mock(ViewSnapshot.class);
    when(snap1.getQuery()).thenReturn(qop1);

    ViewSnapshot snap2 = mock(ViewSnapshot.class);
    when(snap2.getQuery()).thenReturn(qop2);

    eventManager.onViewSnapshots(Arrays.asList(snap1, snap2));
    InOrder inOrder = inOrder(spy1, spy3, spy2);
    inOrder.verify(spy1).onViewSnapshot(snap1);
    inOrder.verify(spy3).onViewSnapshot(snap1);
    inOrder.verify(spy2).onViewSnapshot(snap2);
  }

  @Test
  public void testWillForwardOnOnlineStateChangedCalls() {
    Query query1 = Query.atPath(path("foo/bar"));
    QueryOrPipeline qop1 = new QueryOrPipeline.QueryWrapper(query1);

    SyncEngine syncSpy = mock(SyncEngine.class);
    EventManager eventManager = new EventManager(syncSpy);

    List<Object> events = new ArrayList<>();

    QueryListener spy = mock(QueryListener.class);
    when(spy.getQuery()).thenReturn(qop1);
    doAnswer(
            invocation -> {
              events.add(invocation.getArguments()[0]);
              return false;
            })
        .when(spy)
        .onOnlineStateChanged(any());

    eventManager.addQueryListener(spy);
    assertEquals(Arrays.asList(OnlineState.UNKNOWN), events);
    eventManager.handleOnlineStateChange(OnlineState.ONLINE);
    assertEquals(Arrays.asList(OnlineState.UNKNOWN, OnlineState.ONLINE), events);
  }
}
