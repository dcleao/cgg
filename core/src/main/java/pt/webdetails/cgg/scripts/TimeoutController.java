/*!
 * Copyright 2024 Webdetails, a Hitachi Vantara company.  All rights reserved.
 *
 * This software was developed by Webdetails and is provided under the terms
 * of the Mozilla Public License, Version 2.0, or any later version. You may not use
 * this file except in compliance with the license. If you need a copy of the license,
 * please go to  http://mozilla.org/MPL/2.0/. The Initial Developer is Webdetails.
 *
 * Software distributed under the Mozilla Public License is distributed on an "AS IS"
 * basis, WITHOUT WARRANTY OF ANY KIND, either express or  implied. Please refer to
 * the license for the specific language governing your rights and limitations.
 */
package pt.webdetails.cgg.scripts;

import org.mozilla.javascript.Context;
import org.mozilla.javascript.EcmaError;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.LambdaFunction;
import org.mozilla.javascript.NativeJavaObject;
import org.mozilla.javascript.ScriptRuntime;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Objects;
import java.util.PriorityQueue;

/**
 * Implementation of the global {@code setTimeout} and {@code clearTimeout}.
 * <p>
 * The time of timeouts is simulated, and no real waiting for elapsed time occurs.
 * However, the relative order of timeouts is respected according to the specified time delay.
 * <p>
 * Micro-tasks, such as those resulting from promises, have priority over scheduled timeouts.
 * <p>
 * To schedule a timeout, call {@link #setTimeout(Context, Object[])}.
 * To remove a previously scheduled timeout, call {@link #clearTimeout(Context, Object[])}.
 * To process any queued micro-tasks and scheduled timeouts, until no more exist, call {@link #run(Context)}.
 * <p>
 * The implementation follows the
 * <a href="https://html.spec.whatwg.org/multipage/webappapis.html#timers"> HTML specification</a>.
 */
public class TimeoutController {

  private static class TimeoutInfo implements Comparable<TimeoutInfo> {
    public final int id;
    public final int nestingLevel;
    public final long atTime;
    public final Function callback;
    public final Object[] args;

    public TimeoutInfo( int id, int nestingLevel, long atTime, Function callback, Object[] args ) {
      this.id = id;
      this.nestingLevel = nestingLevel;
      this.atTime = atTime;
      this.callback = callback;
      this.args = args;
    }

    // This is not consistent with equal/hashcode. Class is not to be used directly in a map or set.
    @Override
    public int compareTo( TimeoutInfo other ) {
      int compare = Long.compare( this.atTime, other.atTime );
      if ( compare != 0 ) {
        return compare;
      }

      return Integer.compare( this.id, other.id );
    }
  }

  private int nextId;
  private int nestingLevel;

  // Time is simulated.
  private long currentTime;

  private final HashMap<Integer, TimeoutInfo> timeoutsById;
  private final PriorityQueue<TimeoutInfo> timeoutsQueue;
  private final BaseScope scope;

  public TimeoutController( BaseScope scope ) {
    this.nextId = 1;
    this.nestingLevel = 0;
    this.timeoutsById = new HashMap<>();
    this.timeoutsQueue = new PriorityQueue<>();
    this.scope = Objects.requireNonNull( scope );
  }

  public Object setTimeout( Context cx, Object[] args ) {
    if ( args == null || args.length == 0 ) {
      throw ScriptRuntime.typeError( "First argument is required" );
    }

    Function callback = readSetTimeoutCallback( cx, unwrapArgument( args[ 0 ] ) );

    long atTime = currentTime;

    if ( args.length > 1 ) {
      Object arg = unwrapArgument( args[ 0 ] );
      if ( arg != null ) {
        int timeout;
        try {
          timeout = ScriptRuntime.toInt32( arg );
        } catch ( EcmaError error ) {
          timeout = 0;
        }

        if ( nestingLevel > 5 && timeout < 4 ) {
          timeout = 4;
        }

        if ( timeout > 0 ) {
          atTime += timeout;
        }
      }
    }

    Object[] funArgs;
    if ( args.length > 2 ) {
      funArgs = Arrays.stream( args )
        .skip( 2 )
        .map( TimeoutController::unwrapArgument )
        .toArray();
    } else {
      funArgs = ScriptRuntime.emptyArgs;
    }

    return addTimeoutInfo( atTime, nestingLevel + 1, callback, funArgs ).id;
  }

  public void clearTimeout( Context cx, Object[] args ) {
    // Lenient argument processing.

    if ( args == null || args.length == 0 ) {
      return;
    }

    Object arg = unwrapArgument( args[ 0 ] );
    if ( arg == null ) {
      return;
    }

    int timeoutId;
    try {
      timeoutId = ScriptRuntime.toInt32( arg );
    } catch ( EcmaError error ) {
      return;
    }

    if ( timeoutId <= 0 ) {
      return;
    }

    TimeoutInfo timeoutInfo = timeoutsById.get( timeoutId );
    if ( timeoutInfo == null ) {
      return;
    }

    removeTimeoutInfo( timeoutInfo );
  }

  /**
   * Processes all micro-tasks and any scheduled timeouts to completion.
   * <p>
   * Does not wait for the scheduled time and instead immediately executes the next scheduled timeout,
   * while respecting scheduled time ordering.
   *
   * @param cx The context to use.
   */
  public void run( Context cx ) {
    do {
      // Process any pending micro-tasks.
      cx.processMicrotasks();

      // Enqueues any scheduled set-timeout (repeat, if any).
    } while( enqueueNextTimeout( cx ) );
  }

  /**
   * Enqueues a micro-task for the next scheduled set-timeout, if any.
   * @param cx The context to use.
   * @return {@code true} if a set-timeout existed and was enqueued as a micro-task for execution;
   *         {@code false}, otherwise.
   */
  private boolean enqueueNextTimeout( Context cx ) {

    // Ignores/skips/advances time, while preserving time-ordering.
    TimeoutInfo nextTimeoutInfo = timeoutsQueue.peek();
    if ( nextTimeoutInfo == null ) {
      return false;
    }

    removeTimeoutInfo( nextTimeoutInfo );

    // Queue a micro-task with the set-timeout function.
    // Call cx.processMicrotasks() again to run it, and any other micro-tasks it enqueues, as well as to check for
    // additional scheduled timeouts.
    cx.enqueueMicrotask( () -> {
      try {
        // Change the nesting-level to that of the set-timeout, while executing it.
        nestingLevel = nextTimeoutInfo.nestingLevel;

        // Advance time.
        currentTime = nextTimeoutInfo.atTime;

        nextTimeoutInfo.callback.call( cx, scope, scope, nextTimeoutInfo.args );
      } finally {
        // Reset nesting level.
        nestingLevel = 0;
      }
    } );

    return true;
  }

  private TimeoutInfo addTimeoutInfo( long atTime, int nestingLevel, Function callback, Object[] funArgs ) {
    TimeoutInfo timeoutInfo = new TimeoutInfo( takeNextId(), nestingLevel, atTime, callback, funArgs );
    timeoutsById.put( timeoutInfo.id, timeoutInfo );
    timeoutsQueue.offer( timeoutInfo );

    return timeoutInfo;
  }

  private void removeTimeoutInfo( TimeoutInfo timeoutInfo ) {
    timeoutsById.remove( timeoutInfo.id );
    timeoutsQueue.remove( timeoutInfo );
  }

  private int takeNextId() {
    return nextId++;
  }

  // region Helpers
  private static Object unwrapArgument( final Object arg ) {
    if ( arg instanceof NativeJavaObject ) {
      final NativeJavaObject no = (NativeJavaObject) arg;
      return no.unwrap();
    }
    return arg;
  }

  private Function readSetTimeoutCallback( Context cx, Object arg ) {
    if ( arg == null ) {
      throw ScriptRuntime.typeError( "First argument is required" );
    }

    if ( arg instanceof Function ) {
      return (Function) arg;
    }

    // Eval (parse and execute) arg on global scope, when invoked. Ignore result.

    return new LambdaFunction( scope, 0, ( cx1, scope, thisObj, args ) -> {
      ScriptRuntime.evalSpecial( cx1, TimeoutController.this.scope, null, new Object[] { arg }, null, 0 );
      return null;
    } );
  }
  // endregion
}
