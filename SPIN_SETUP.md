# Lucky Wheel (Spin) - setup and how it works

## What was added
* **Player app**: banner "Lucky Wheel - SPIN" on the match list of every game the admin turned on, a Lucky Wheel screen
  (wheel, lock status, match progress, daily spins), Spin History, a "Congratulations" popup with confetti and the coin amount,
  and a "Lucky Wheel" row in Account (only while the spin is ON).
* **Admin app**: new tile **Lucky Wheel (Spin)** (staff need the new permission `spin_manage`).
* **No backend / Railway needed.** The spin runs inside the app and Firebase only. The reward and the wallet credit are written in ONE
  atomic Firebase write, and the **database rules** check it (see "What the rules enforce").
* **Firebase rules**: nodes `spinConfig`, `spinUserCfg`, `spinState`, `spinBonus`, `spinHistory`, `spinLog` + a rule that lets the wallet go up
  only together with a valid spin.

## Steps to go live (only two)
1. Firebase console -> Realtime Database -> Rules -> paste the new `database.rules.json` -> **Publish**.
2. Build and install the new Player app and Admin app.
Then Admin app -> **Lucky Wheel (Spin)** -> switch ON, choose the games, press **SAVE SETTINGS once** (this also saves the highest wheel reward the rules use).

## Admin controls
| Setting | Meaning |
|---|---|
| ON / OFF | Master switch. Off = nobody can spin, banners are hidden. |
| Matches to play for 1 spin + minimum entry fee | Task, for example 8 matches with entry fee 10. A match counts when the player joined it and it is **ongoing** (or finished). Each spin uses up that many matches. 0 = no task. |
| Count matches played before the first spin | Off (default) = only matches played after the player first opens the wheel count. |
| Spins per day | 0 = no limit. Day = Indian time (IST). |
| Minimum days between two spins | 0 = none, 0.5 = 12 hours. Works together with the daily limit (the stricter one wins). |
| Reward lock for ALL players | Lock 5 = only 2 or 5. 6 to 10 = up to 10. 11 to 25 = up to 25. 26 to 50 = up to 50. 51 to 80 = up to 150 (never 200 / 500 / 1000). 81 or more = whole wheel (up to 1000). 0 = only 2 coins. The database rules use the same steps, so publish `database.rules.json` once. |
| Reward added to | Join wallet (default, used for entry fees) or Winning wallet (withdrawable). |
| Wheel rewards | One line per piece, `coin:chance`. Bigger chance number = comes more often. 2 to 12 pieces. |
| Games with Spin banner | Switch per game (ALL ON / ALL OFF buttons). |
| Player control | Search a username -> own lock for that player, or block his spin. A player lock beats the lock for all. |
| Recent spins | Last 30 spins of all players. |

## What the rules enforce (tested in `backend/test/spin-rules.test.js`)
* Lucky Wheel ON, player not blocked, wallet chosen by the admin.
* Reward is not above the lock (player lock beats the lock for all) and not above the highest reward of the wheel.
* Daily limit and minimum days between spins (the day / time come from the Firebase server clock).
* Admin free spins: only if the player really has one, exactly one is taken, and they skip task, daily limit and cooldown.
* The wallet goes up by exactly the reward, in the same write as the spin. A wallet raise without a spin is refused.
* Every spin is saved in wallet history (type `spin`), Spin History and the admin's Recent spins.

## Honest limit of the no-server way
The random pick and the "played N matches" count are done on the phone. The rules cap what a changed (hacked) app could do - it can never
win more than the lock / highest reward, never more often than the daily limit, never skip the cooldown - but the rules cannot prove the
reward was random or that the matches were played. Rewards go to the **Join wallet** (not withdrawable) by default, which keeps this risk small.
If you ever need full protection, the older server version (`backend/spin.js`) can be switched back on.
