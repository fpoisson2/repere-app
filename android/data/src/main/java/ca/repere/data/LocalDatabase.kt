package ca.repere.data

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Entity(tableName = "drinks", indices = [Index(value = ["serverId"], unique = true)])
data class DrinkEntity(
    @PrimaryKey val clientId: String,
    val serverId: Long?,
    val name: String,
    val type: String?,
    val volumeMl: Double,
    val abvPercent: Double,
    val quantity: Int,
    val startedAt: String,
    val durationMinutes: Int,
    val notes: String?,
    val active: Boolean,
    val dirty: Boolean,
    val deleted: Boolean,
    val pendingMutationId: String?,
)

@Entity(tableName = "presets")
data class PresetEntity(
    @PrimaryKey val serverId: Long,
    val name: String,
    val type: String,
    val volumeMl: Double,
    val abvPercent: Double,
)

@Entity(tableName = "sync_state")
data class SyncState(@PrimaryKey val account: String = "default", val cursor: Long = 0)

@Entity(tableName="health_aggregates")
data class HealthAggregateEntity(@PrimaryKey val id:String,val localDate:String,val recordType:String,
    val originPackage:String,val payload:String,val dirty:Boolean=true)

@Dao
interface RepereDao {
    @Query("SELECT * FROM drinks WHERE deleted = 0 ORDER BY startedAt DESC")
    fun observeDrinks(): Flow<List<DrinkEntity>>

    @Query("SELECT * FROM presets ORDER BY serverId")
    fun observePresets(): Flow<List<PresetEntity>>

    @Query("SELECT * FROM drinks WHERE dirty = 1 ORDER BY startedAt")
    suspend fun pendingDrinks(): List<DrinkEntity>

    @Query("SELECT * FROM drinks WHERE serverId = :serverId LIMIT 1")
    suspend fun findByServerId(serverId: Long): DrinkEntity?

    @Query("SELECT * FROM drinks WHERE clientId = :clientId LIMIT 1")
    suspend fun findByClientId(clientId: String): DrinkEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putDrink(drink: DrinkEntity)

    @Query("DELETE FROM drinks WHERE clientId = :clientId")
    suspend fun deleteDrink(clientId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putPresets(presets: List<PresetEntity>)

    @Query("DELETE FROM presets")
    suspend fun clearPresets()

    @Query("SELECT COUNT(*) FROM presets")
    suspend fun presetCount():Int

    @Query("SELECT * FROM sync_state WHERE account = 'default'")
    suspend fun syncState(): SyncState?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun setSyncState(state: SyncState)

    @Query("SELECT * FROM health_aggregates ORDER BY localDate DESC, recordType")
    fun observeHealth():Flow<List<HealthAggregateEntity>>

    @Query("SELECT * FROM health_aggregates WHERE dirty = 1")
    suspend fun pendingHealth():List<HealthAggregateEntity>

    @Insert(onConflict=OnConflictStrategy.REPLACE)
    suspend fun putHealth(rows:List<HealthAggregateEntity>)

    @Query("UPDATE health_aggregates SET dirty = 0 WHERE id IN (:ids)")
    suspend fun markHealthSynced(ids:List<String>)
}

@Database(entities = [DrinkEntity::class, PresetEntity::class, SyncState::class,HealthAggregateEntity::class], version = 2, exportSchema = true)
abstract class RepereDatabase : RoomDatabase() {
    abstract fun dao(): RepereDao

    companion object {
        @Volatile private var instance: RepereDatabase? = null
        private val MIGRATION_1_2=object:Migration(1,2){override fun migrate(db:SupportSQLiteDatabase){
            db.execSQL("CREATE TABLE IF NOT EXISTS health_aggregates (id TEXT NOT NULL PRIMARY KEY, localDate TEXT NOT NULL, recordType TEXT NOT NULL, originPackage TEXT NOT NULL, payload TEXT NOT NULL, dirty INTEGER NOT NULL)")
        }}
        fun get(context: Context): RepereDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, RepereDatabase::class.java, "repere.db")
                .addMigrations(MIGRATION_1_2).build().also { instance = it }
        }
    }
}
