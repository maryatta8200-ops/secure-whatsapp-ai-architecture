package com.securewa.app

import android.app.Application

/**
 * Application entry point.
 *
 * Milestone 1: no background work is scheduled from here. The project requirement
 * is event-driven processing, so workers are registered only when the feature
 * that needs them is implemented and verified (see docs/MILESTONES.md). Starting
 * a periodic poll in Application.onCreate would burn wakeups for a feature that
 * does not exist yet.
 */
class SecureWaApplication : Application()
