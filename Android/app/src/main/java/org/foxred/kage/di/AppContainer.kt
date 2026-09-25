package org.foxred.kage.di

import android.content.Context
import androidx.room.Room
import org.foxred.kage.data.local.MailDatabase
import org.foxred.kage.data.repository.RoomMailRepository
import org.foxred.kage.data.seed.DemoMail
import org.foxred.kage.domain.repository.MailRepository

/** Application-scoped composition root. UI depends only on the repository interface. */
class AppContainer(context: Context) {
    private val database =
        Room.databaseBuilder(context.applicationContext, MailDatabase::class.java, "kage-mail.db")
            .build()
    val mailRepository: MailRepository =
        RoomMailRepository(
            database,
            context.applicationContext,
            DemoMail(context.applicationContext),
        )
}
