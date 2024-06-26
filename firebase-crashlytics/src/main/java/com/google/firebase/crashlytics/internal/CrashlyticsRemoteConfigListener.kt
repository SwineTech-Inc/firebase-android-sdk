/*
 * Copyright 2023 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.firebase.crashlytics.internal

import com.google.firebase.crashlytics.internal.metadata.RolloutAssignment
import com.google.firebase.crashlytics.internal.metadata.UserMetadata
import com.google.firebase.remoteconfig.interop.rollouts.RolloutsState
import com.google.firebase.remoteconfig.interop.rollouts.RolloutsStateSubscriber

class CrashlyticsRemoteConfigListener(private val userMetadata: UserMetadata) :
  RolloutsStateSubscriber {
  override fun onRolloutsStateChanged(rolloutsState: RolloutsState) {
    userMetadata.updateRolloutsState(
      rolloutsState.rolloutAssignments.map { it ->
        RolloutAssignment.create(
          it.rolloutId,
          it.parameterKey,
          it.parameterValue,
          it.variantId,
          it.templateVersion
        )
      }
    )
    Logger.getLogger().d("Updated Crashlytics Rollout State")
  }
}
