package com.alexdiab.stadiatv.mobile

import com.alexdiab.stadiatv.domain.GameShot
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Basket-relative feet, matching BasketballCourtCoordinateTransformer. Never clamp a reported attempt. */
internal object PhoneBasketballCourt {
    data class Point(val u: Float, val v: Float)
    fun point(x: Float, y: Float) = Point((x + 25f) / 50f, 1f - (y + 5.25f) / 47f)
    fun onCourt(shot: GameShot) = shot.x.isFinite() && shot.y.isFinite() && shot.x in -25f..25f && shot.y in -5.25f..41.75f
    fun beyondHalf(shot: GameShot) = shot.y.isFinite() && shot.y > 41.75f
    fun markerRadius(value: Int?) = if (value == 3) 5f else 4f
    fun restrictedArea(): List<Point> = (0..36).map { step ->
        val angle = step * Math.PI / 36
        point((4 * cos(angle)).toFloat(), (4 * sin(angle)).toFloat())
    }
    fun threePointLine(): List<Point> {
        val radius = 23.75
        val theta = acos(22 / radius)
        val cornerY = sqrt(radius * radius - 22 * 22).toFloat()
        return listOf(point(-22f, -5.25f), point(-22f, cornerY)) + (0..48).map { step ->
            val angle = Math.PI - theta - (Math.PI - 2 * theta) * step / 48
            point((radius * cos(angle)).toFloat(), (radius * sin(angle)).toFloat())
        } + point(22f, -5.25f)
    }
    fun summary(shots: List<GameShot>) = "Shot chart. ${shots.count { it.made }} made of ${shots.size} attempts."
}
