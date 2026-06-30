/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.glimpse.avif

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.Environment
import java.io.File
import java.util.ArrayDeque
import java.util.concurrent.Executors

class AvifDatabaseHelper(context: Context) : SQLiteOpenHelper(context, "avif_db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE avif_files (path TEXT PRIMARY KEY) WITHOUT ROWID")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.rawQuery("PRAGMA journal_mode = WAL", null).use { it.moveToFirst() }
        db.rawQuery("PRAGMA synchronous = NORMAL", null).use { it.moveToFirst() }
        db.rawQuery("PRAGMA temp_store = MEMORY", null).use { it.moveToFirst() }
    }
}

class AvifScanner(private val context: Context) {
    private val dbHelper = AvifDatabaseHelper(context)
    private val executor = Executors.newSingleThreadExecutor()

    fun getCachedPaths(): List<String> {
        val cachedList = mutableListOf<String>()
        dbHelper.readableDatabase.rawQuery("SELECT path FROM avif_files", null).use { cursor ->
            while (cursor.moveToNext()) {
                cachedList.add(cursor.getString(0))
            }
        }
        return cachedList
    }

    fun openMedia(path: String): String? {
        if (File(path).exists()) {
            return path
        }
        removeInvalidMedia(path)
        return null
    }

    fun removeInvalidMedia(path: String) {
        executor.execute {
            dbHelper.writableDatabase.delete("avif_files", "path = ?", arrayOf(path))
        }
    }

    fun startScan(onResult: (List<String>) -> Unit) {
        executor.execute {
            val db = dbHelper.writableDatabase
            val existingPaths = HashSet<String>(2048)
            val invalidPaths = ArrayList<String>()

            db.rawQuery("SELECT path FROM avif_files", null).use { cursor ->
                while (cursor.moveToNext()) {
                    val path = cursor.getString(0)
                    if (File(path).exists()) {
                        existingPaths.add(path)
                    } else {
                        invalidPaths.add(path)
                    }
                }
            }

            if (invalidPaths.isNotEmpty()) {
                db.beginTransactionNonExclusive()
                try {
                    val deleteStmt = db.compileStatement("DELETE FROM avif_files WHERE path = ?")
                    for (i in invalidPaths.indices) {
                        deleteStmt.bindString(1, invalidPaths[i])
                        deleteStmt.executeUpdateDelete()
                    }
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
            }

            val newPaths = ArrayList<String>()
            val rootFile = Environment.getExternalStorageDirectory()
            val directoryStack = ArrayDeque<File>(128)
            directoryStack.push(rootFile)

            while (directoryStack.isNotEmpty()) {
                val currentFile = directoryStack.pop()
                val innerFiles = currentFile.listFiles()

                if (innerFiles != null) {
                    for (i in innerFiles.indices) {
                        val file = innerFiles[i]
                        val name = file.name
                        if (file.isDirectory) {
                            if (!name.equals("Android", ignoreCase = true)) {
                                directoryStack.push(file)
                            }
                        } else {
                            if (name.endsWith(".avif", ignoreCase = true)) {
                                val absolutePath = file.absolutePath
                                if (!existingPaths.contains(absolutePath)) {
                                    newPaths.add(absolutePath)
                                }
                            }
                        }
                    }
                }
            }

            if (newPaths.isNotEmpty()) {
                db.beginTransactionNonExclusive()
                try {
                    val insertStmt = db.compileStatement("INSERT INTO avif_files (path) VALUES (?)")
                    for (i in newPaths.indices) {
                        insertStmt.bindString(1, newPaths[i])
                        insertStmt.executeInsert()
                    }
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
            }

            val finalList = mutableListOf<String>()
            db.rawQuery("SELECT path FROM avif_files", null).use { cursor ->
                while (cursor.moveToNext()) {
                    finalList.add(cursor.getString(0))
                }
            }
            onResult(finalList)
        }
    }
}
