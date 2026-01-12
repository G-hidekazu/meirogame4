package com.example.meirogame4

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

class MainActivity : AppCompatActivity(), SensorEventListener {
    private lateinit var sensorManager: SensorManager
    private lateinit var accelerometer: Sensor
    private lateinit var mazeView: MazeView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        mazeView = MazeView(this)
        setContentView(mazeView)
    }

    override fun onResume() {
        super.onResume()
        sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_GAME)
        mazeView.start()
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
        mazeView.stop()
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return
        val ax = -event.values[0]
        val ay = event.values[1]
        mazeView.updateAcceleration(ax, ay)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}

private class MazeView(context: Context) : View(context) {
    private val rows = 10
    private val cols = 8
    private val horizontalWalls = Array(rows + 1) { BooleanArray(cols) { true } }
    private val verticalWalls = Array(rows) { BooleanArray(cols + 1) { true } }
    private val cellVisited = Array(rows) { BooleanArray(cols) }

    private var cellSize = 0f
    private var offsetX = 0f
    private var offsetY = 0f

    private val wallPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.DKGRAY
        strokeWidth = 8f
    }
    private val ballPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.RED }
    private val goalPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.GREEN }
    private val startPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLUE }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textAlign = Paint.Align.CENTER
        textSize = 48f
    }

    private var ballX = 0f
    private var ballY = 0f
    private var ballRadius = 0f
    private var velocityX = 0f
    private var velocityY = 0f
    private var accelX = 0f
    private var accelY = 0f
    private var lastUpdateTime = 0L
    private var running = false
    private var reachedGoal = false

    private val updateRunnable = object : Runnable {
        override fun run() {
            if (!running) return
            val now = System.nanoTime()
            if (lastUpdateTime == 0L) lastUpdateTime = now
            val deltaTime = (now - lastUpdateTime) / 1_000_000_000f
            lastUpdateTime = now
            stepPhysics(deltaTime)
            invalidate()
            postOnAnimation(this)
        }
    }

    init {
        generateMaze(0, 0)
    }

    fun start() {
        if (running) return
        running = true
        lastUpdateTime = 0L
        postOnAnimation(updateRunnable)
    }

    fun stop() {
        running = false
    }

    fun updateAcceleration(x: Float, y: Float) {
        accelX = x
        accelY = y
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val availableWidth = w.toFloat() * 0.9f
        val availableHeight = h.toFloat() * 0.9f
        cellSize = min(availableWidth / cols, availableHeight / rows)
        offsetX = (w - cellSize * cols) / 2f
        offsetY = (h - cellSize * rows) / 2f
        ballRadius = cellSize * 0.25f
        resetBall()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        drawMaze(canvas)
        drawStartGoal(canvas)
        drawBall(canvas)
        if (reachedGoal) {
            canvas.drawText("ゴール！", width / 2f, offsetY * 0.6f, textPaint)
        }
    }

    private fun drawMaze(canvas: Canvas) {
        val left = offsetX
        val top = offsetY
        for (row in 0..rows) {
            for (col in 0 until cols) {
                if (horizontalWalls[row][col]) {
                    val x1 = left + col * cellSize
                    val y1 = top + row * cellSize
                    val x2 = x1 + cellSize
                    canvas.drawLine(x1, y1, x2, y1, wallPaint)
                }
            }
        }
        for (row in 0 until rows) {
            for (col in 0..cols) {
                if (verticalWalls[row][col]) {
                    val x1 = left + col * cellSize
                    val y1 = top + row * cellSize
                    val y2 = y1 + cellSize
                    canvas.drawLine(x1, y1, x1, y2, wallPaint)
                }
            }
        }
    }

    private fun drawStartGoal(canvas: Canvas) {
        val startCenterX = offsetX + cellSize * 0.5f
        val startCenterY = offsetY + cellSize * 0.5f
        val goalCenterX = offsetX + cellSize * (cols - 0.5f)
        val goalCenterY = offsetY + cellSize * (rows - 0.5f)
        canvas.drawCircle(startCenterX, startCenterY, ballRadius * 0.6f, startPaint)
        canvas.drawCircle(goalCenterX, goalCenterY, ballRadius * 0.8f, goalPaint)
    }

    private fun drawBall(canvas: Canvas) {
        canvas.drawCircle(ballX, ballY, ballRadius, ballPaint)
    }

    private fun stepPhysics(deltaTime: Float) {
        if (cellSize == 0f) return
        val accelFactor = 12f
        val friction = 0.9f
        velocityX += accelX * accelFactor * deltaTime
        velocityY += accelY * accelFactor * deltaTime
        velocityX *= friction
        velocityY *= friction

        val nextX = ballX + velocityX * cellSize
        val nextY = ballY + velocityY * cellSize
        val clampedX = clampWithWalls(ballX, nextX, ballY, true)
        val clampedY = clampWithWalls(ballY, nextY, clampedX, false)
        ballX = clampedX
        ballY = clampedY
        if (!reachedGoal && isAtGoal()) {
            reachedGoal = true
        }
    }

    private fun clampWithWalls(current: Float, next: Float, otherAxis: Float, isHorizontal: Boolean): Float {
        val minValue = if (isHorizontal) offsetX + ballRadius else offsetY + ballRadius
        val maxValue = if (isHorizontal) offsetX + cellSize * cols - ballRadius else offsetY + cellSize * rows - ballRadius
        var result = min(max(next, minValue), maxValue)
        val (row, col) = positionToCell(result, otherAxis, isHorizontal)

        if (isHorizontal) {
            if (next > current && verticalWalls[row][col + 1]) {
                val wallX = offsetX + (col + 1) * cellSize
                if (result + ballRadius > wallX) {
                    result = wallX - ballRadius
                    velocityX = 0f
                }
            } else if (next < current && verticalWalls[row][col]) {
                val wallX = offsetX + col * cellSize
                if (result - ballRadius < wallX) {
                    result = wallX + ballRadius
                    velocityX = 0f
                }
            }
        } else {
            if (next > current && horizontalWalls[row + 1][col]) {
                val wallY = offsetY + (row + 1) * cellSize
                if (result + ballRadius > wallY) {
                    result = wallY - ballRadius
                    velocityY = 0f
                }
            } else if (next < current && horizontalWalls[row][col]) {
                val wallY = offsetY + row * cellSize
                if (result - ballRadius < wallY) {
                    result = wallY + ballRadius
                    velocityY = 0f
                }
            }
        }
        return result
    }

    private fun positionToCell(primary: Float, secondary: Float, isHorizontal: Boolean): Pair<Int, Int> {
        val x = if (isHorizontal) primary else secondary
        val y = if (isHorizontal) secondary else primary
        val col = min(cols - 1, max(0, ((x - offsetX) / cellSize).toInt()))
        val row = min(rows - 1, max(0, ((y - offsetY) / cellSize).toInt()))
        return row to col
    }

    private fun isAtGoal(): Boolean {
        val goalX = offsetX + cellSize * (cols - 0.5f)
        val goalY = offsetY + cellSize * (rows - 0.5f)
        val dx = ballX - goalX
        val dy = ballY - goalY
        return sqrt(dx * dx + dy * dy) < ballRadius * 0.8f
    }

    private fun resetBall() {
        ballX = offsetX + cellSize * 0.5f
        ballY = offsetY + cellSize * 0.5f
        velocityX = 0f
        velocityY = 0f
        reachedGoal = false
    }

    private fun generateMaze(startRow: Int, startCol: Int) {
        for (row in 0 until rows) {
            for (col in 0 until cols) {
                cellVisited[row][col] = false
            }
        }
        val stack = ArrayDeque<Pair<Int, Int>>()
        stack.add(startRow to startCol)
        cellVisited[startRow][startCol] = true
        while (stack.isNotEmpty()) {
            val (row, col) = stack.last()
            val neighbors = mutableListOf<Pair<Int, Int>>()
            if (row > 0 && !cellVisited[row - 1][col]) neighbors.add(row - 1 to col)
            if (row < rows - 1 && !cellVisited[row + 1][col]) neighbors.add(row + 1 to col)
            if (col > 0 && !cellVisited[row][col - 1]) neighbors.add(row to col - 1)
            if (col < cols - 1 && !cellVisited[row][col + 1]) neighbors.add(row to col + 1)
            if (neighbors.isEmpty()) {
                stack.removeLast()
                continue
            }
            val (nextRow, nextCol) = neighbors.random()
            if (nextRow == row - 1) {
                horizontalWalls[row][col] = false
            } else if (nextRow == row + 1) {
                horizontalWalls[row + 1][col] = false
            } else if (nextCol == col - 1) {
                verticalWalls[row][col] = false
            } else if (nextCol == col + 1) {
                verticalWalls[row][col + 1] = false
            }
            cellVisited[nextRow][nextCol] = true
            stack.add(nextRow to nextCol)
        }
        horizontalWalls[0][0] = true
        verticalWalls[0][0] = true
    }
}
