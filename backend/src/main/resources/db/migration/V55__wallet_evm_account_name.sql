-- V54 converted ETHEREUM wallets to EVM and re-pointed their accounts, but left
-- account.name alone. An unlabelled wallet's account was named from the chain
-- ("ETHEREUM Wallet", see WalletSyncService.resolveAccount), and resolveAccount only
-- refreshes balance/lastSyncedAt/ticker for an account it finds -- never the name. So a
-- migrated wallet would display the retired chain name forever, even though it now also
-- tracks BNB, POL and AVAX.
--
-- Rewrite only the exact default name, and only for accounts V54 re-pointed: a
-- user-chosen label must survive untouched.
UPDATE account
   SET name = 'EVM Wallet'
 WHERE name = 'ETHEREUM Wallet'
   AND external_account_id LIKE 'wallet_evm_%';
