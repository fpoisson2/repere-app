package ca.repere.wear

import android.content.Context
/** State is pushed by the paired phone through the Wear Data Layer. */
object StateCache {
    suspend fun refresh(context: Context) { context.getSharedPreferences("repere",Context.MODE_PRIVATE) }
}
