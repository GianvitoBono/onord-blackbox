package com.opnord.blackbox.service

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority

/** Small boundary around Play Services so service persistence can be tested independently. */
interface LocationCollector {
    fun start(onLocation: (Location) -> Unit)
    fun stop()
}

class FusedLocationCollector(context: Context) : LocationCollector {
    private val client = LocationServices.getFusedLocationProviderClient(context)
    private var callback: LocationCallback? = null

    @SuppressLint("MissingPermission")
    override fun start(onLocation: (Location) -> Unit) {
        if (callback != null) return
        val listener = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.locations.forEach(onLocation)
            }
        }
        callback = listener
        client.requestLocationUpdates(
            LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1_000L)
                .setMinUpdateIntervalMillis(1_000L)
                .setWaitForAccurateLocation(false)
                .build(), listener, android.os.Looper.getMainLooper()
        )
    }

    override fun stop() {
        callback?.let(client::removeLocationUpdates)
        callback = null
    }
}
