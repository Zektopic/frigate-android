package com.zektopic.frigate.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationSettingsTest {

    @Test
    fun happyPathNotifies() {
        val s = NotificationSettings(notificationsEnabled = true, notifyOnMotion = true)
        assertTrue(s.shouldNotify("front_camera"))
    }

    @Test
    fun masterSwitchOffSuppressesAll() {
        val s = NotificationSettings(notificationsEnabled = false, notifyOnMotion = true)
        assertFalse(s.shouldNotify("front_camera"))
    }

    @Test
    fun motionAlertsOffSuppresses() {
        val s = NotificationSettings(notificationsEnabled = true, notifyOnMotion = false)
        assertFalse(s.shouldNotify("front_camera"))
    }

    @Test
    fun mutedCameraSuppressedButOthersNotify() {
        val s = NotificationSettings(mutedCameraIds = setOf("front_camera"))
        assertFalse(s.shouldNotify("front_camera"))
        assertTrue(s.shouldNotify("back_garden"))
    }
}
