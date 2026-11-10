# Room, WorkManager and Compose ship their own consumer rules.
-keep class com.example.autoposter.PostWorker { *; }
-keep class com.example.autoposter.SyncWorker { *; }
