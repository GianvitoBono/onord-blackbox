# Milestones

- [x] M0 project skeleton: Android/Kotlin, Room/WAL, DataStore/WorkManager dependencies, foreground service, power receiver, pure trip state machine, unit test setup.
- [x] M1 vertical slice foundation: USB power starts Room trip, 1 Hz local sample persistence, disconnect grace timer, trip closure, crash recovery.
- [ ] M1 complete: real fused GPS, permission/onboarding and diagnostic screen, settings, exported/rotated logs, fakeable collectors and service integration tests.
- [ ] M2 OBD abstraction, Bluetooth device setup, PID support discovery, scheduler/parser/reconnect.
- [ ] M3 idempotent API batching, ACK-bound sync marking, Wi-Fi/any-network option.
- [ ] M4 embedded-mode research and device-specific procedure.
