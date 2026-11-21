# World chat (WhatsApp style, text only)

1. Publish the new `database.rules.json` in Firebase (Realtime Database -> Rules). Without it the chat does not work.
2. Players: when the admin turns World chat ON, the round chat icon (with a red number of new messages) takes the place of the globe in the Home header; when it is turned OFF the globe comes back and the chat icon disappears. Chat is OFF until the admin turns it ON. Long-press a message to report it.
3. Admin app (and Staff app with notifications_send) -> "World chat" tile: chat ON/OFF, players online and joined members, extra blocked words, reports, delete message, mute / unmute. Admin only: chat title, group info text, chat icon picture, chat admins, write in chat as OWNER.
   - chat ON/OFF, players online now, extra blocked words, reports, delete message, mute / unmute
   - Admin only: chat title, chat icon picture, chat admins (make a player ADMIN by username), write in chat as OWNER
4. Badges: the admin account shows OWNER, chat admins show ADMIN. Chat admins can long-press a message to delete it or mute the player (1 / 24 hours).
5. "Online" = players who have the chat screen open (updates every 30 seconds).
6. Messages older than 7 days (or beyond the newest 200) are removed when the admin opens the World chat screen.
7. Group info (tap the chat title or the 3 dots): picture, title, text, button to turn off the red number on this phone, total joined members and the member list (OWNER / ADMIN badges, "(You)"). A player is added to the member list when he opens the chat for the first time.

## v12: World chat room in the Admin / Staff app, stickers, voice
1. Publish the new `database.rules.json` again (new: `kind`, `sticker`, `voiceId`, `durationMs` on chat messages and the new `worldChat/voice` node for the audio). Without it stickers and voice messages cannot be sent.
2. Admin app (and Staff app with notifications_send): the "World chat" tile now opens the chat ROOM itself (live messages, write, stickers, voice). The old moderation screen (ON/OFF, title, icon, blocked words, reports, mutes, chat admins) is the "Chat settings" tile, or the gear icon on top of the room.
3. The admin account writes as OWNER: a gold "OWNER" tag is shown above the name. Staff write as ADMIN. Chat admins (made by the owner) show an ADMIN tag beside the name, also on their older messages.
4. Make a chat admin from the room: long-press any player's message -> "MAKE CHAT ADMIN" (owner only). Same place: "REMOVE CHAT ADMIN", "MUTE PLAYER", "DELETE MESSAGE".
5. Stickers: smiley button next to the text box. They are drawn by the app (Stickers.kt, same list in the player, admin and staff app). To add one, add a line in all three Stickers.kt files.
6. Voice: mic button (shown when the text box is empty). Players record up to 30 seconds, the admin up to 60 seconds. Audio is stored in `worldChat/voice/<voiceId>` and is removed together with its message (delete, or the automatic clean-up of messages older than 7 days / beyond 200).
7. Players need the microphone permission (the app already asks when the mic button is pressed).
8. Voice clips (the grid of play buttons): the mic-in-the-box button next to the smiley opens it for players and admins. Tap a clip = listen, long-press = send it into the chat as a voice message.
   The owner adds clips in the Admin app: World chat room -> clip panel -> "NEW CLIP" (record up to 15 seconds, give a name, SAVE). Long-press a clip in the admin app -> SEND IN CHAT or DELETE CLIP.
   Firebase: worldChat/clips/<id> = { name, durationMs, ts }, audio in worldChat/voice/clip_<id>. A clip's audio is NOT removed when a chat message that uses it is deleted. Publish the latest database.rules.json.

## v19: chat icon rules
1. The round chat icon in the Home header is PERMANENT (built-in icon, red number of new messages, shows `99+` when there are more than 99). The chat picture set by the owner / chat admins only shows INSIDE the chat (chat header + Group info).
2. A chat admin (made by the owner) sees a "Change chat icon" button in Group info (player app). Nothing else of the chat settings is open to chat admins. Publish the latest `database.rules.json` (new: chat admins may write `settings/worldChatIcon` with a `dbimg:` value and add NEW images to `imageData`).
3. Owner (Admin app) -> World chat room -> clip panel -> NEW CLIP: record up to 15 s, or PICK AUDIO FILE (max 30 s / 800 KB) from the phone. Clips already added stay as they are.

## v20: 26 voice clips from the reference video
1. The Admin app now contains 26 voice clips (`admin/src/main/res/raw/vv01.m4a` .. `vv26.m4a`, cut from the reference video). The first time the OWNER opens the World chat room, they are added automatically as normal clips named "Voice 1" .. "Voice 26" (clips that already exist are never touched; it continues where it stopped if the app is closed in the middle). Everybody then sees them in the clip panel (tap = listen, long-press = send).
2. Owner: long-press a clip in the Admin app -> RENAME CLIP / SEND IN CHAT / DELETE CLIP. A deleted clip does not come back.
3. Publish the latest `database.rules.json` (clip length limit is now 30 seconds).

## v21: voice clips re-cut and re-ordered
1. The 26 clips were cut wrongly (started ~1.3 s too early, tail cut off, two clips joined into one, two clips missing, one clip twice). They are now cut again from the grid video (`admin/src/main/res/raw/vw01.m4a` .. `vw26.m4a`).
2. Order = the buttons of the video, row by row (3 per row, last row 2): Voice 1 = first button ... Voice 26 = last button.
3. The first time the OWNER opens the World chat room after updating, the old clips (vv01..vv26) are removed and the 26 new ones are added (names "Voice 1" .. "Voice 26"). Clips the owner added himself stay. Old voice messages in the chat that used the removed clips can no longer be played.

## v22: Virtual slots grouped by game
Admin app -> Virtual Slots now opens the game tiles first (same as the Match screen: FREE, LW 1 VS 1, FULL MAP, CS 1 VS 1 ... with the number of matches). Tap a game to see only its matches, with search inside that game. Back arrow goes back to the game tiles.
