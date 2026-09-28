package dev.opencode.android.core.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.turbine.test
import dev.opencode.android.core.database.dao.ServerDao
import dev.opencode.android.core.database.entity.ServerEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class ServerDaoTest {

    private lateinit var db: OpenCodeDatabase
    private lateinit var dao: ServerDao

    @Before
    fun createDb() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, OpenCodeDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.serverDao()
    }

    @After
    @Throws(IOException::class)
    fun closeDb() {
        db.close()
    }

    @Test
    fun insertAndGetById() = runTest {
        val server = ServerEntity(
            id = "server-1",
            name = "Local Dev",
            baseUrl = "http://127.0.0.1:4096",
            isDefault = true,
        )
        dao.insert(server)

        val retrieved = dao.getById("server-1")
        assertNotNull(retrieved)
        assertEquals("Local Dev", retrieved!!.name)
        assertEquals("http://127.0.0.1:4096", retrieved.baseUrl)
        assertTrue(retrieved.isDefault)
    }

    @Test
    fun setDefaultServerClearsPreviousDefault() = runTest {
        val server1 = ServerEntity(id = "s1", name = "Server 1", baseUrl = "http://localhost:4096", isDefault = true)
        val server2 = ServerEntity(id = "s2", name = "Server 2", baseUrl = "http://localhost:4097", isDefault = false)

        dao.insert(server1)
        dao.insert(server2)

        dao.setDefaultServer("s2")

        val s1 = dao.getById("s1")
        val s2 = dao.getById("s2")

        assertEquals(false, s1!!.isDefault)
        assertEquals(true, s2!!.isDefault)

        val defaultServer = dao.getDefaultServer()
        assertEquals("s2", defaultServer?.id)
    }

    @Test
    fun observeAllFlow() = runTest {
        dao.observeAll().test {
            assertEquals(emptyList<ServerEntity>(), awaitItem())

            val server = ServerEntity(id = "s1", name = "Server 1", baseUrl = "http://localhost:4096")
            dao.insert(server)

            val list = awaitItem()
            assertEquals(1, list.size)
            assertEquals("s1", list[0].id)
        }
    }

    @Test
    fun deleteByIdRemovesEntity() = runTest {
        val server = ServerEntity(id = "s1", name = "Server 1", baseUrl = "http://localhost:4096")
        dao.insert(server)
        assertEquals(1, dao.count())

        dao.deleteById("s1")
        assertEquals(0, dao.count())
        assertNull(dao.getById("s1"))
    }
}
