# Voice messages (Admin / Staff -> Players)

- Admin / Staff app -> Send notification -> green box: RECORD VOICE (max 60 s) -> PLAY -> SEND. Works for All players, one match, or one player.
- Players hear it in Account -> Voice messages (the push opens that list). It works like a normal notification: **only the player deletes it**, from his own list (voiceHidden/<uid>). Admin / Staff have no delete button.
- Storage (no Firebase Storage needed): voiceData/<id>/c0..cN (audio, about 240 KB per minute), voiceMessages/<id> and voiceDirect/<uid>/<id> (info).
- Publish the latest `database.rules.json` (nodes voiceMessages, voiceDirect, voiceData, voiceHidden).
