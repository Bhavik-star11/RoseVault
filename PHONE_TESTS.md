# Phone and cloud tests

The phone UI validates invalid numbers locally and exercises offline continuation; it never sends real SMS. tests/rls.sql.in verifies the protected phone RPC and access policies with rolled-back fixtures. SupabaseTransportDeviceTest is an optional controlled transport integration check, not a phone-OTP bypass in the production app. See VALIDATION.md for actual results.
