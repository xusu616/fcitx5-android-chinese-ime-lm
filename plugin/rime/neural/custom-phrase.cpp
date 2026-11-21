// SPDX-License-Identifier: MIT
#include "custom-phrase-format.h"
#include "neural-reranker.h"

#include <rime/config.h>
#include <rime/dict/table_db.h>
#include <rime/dict/user_dictionary.h>
#include <rime/registry.h>
#include <rime/schema.h>
#include <rime/ticket.h>

namespace {
constexpr char kEqualsPhraseDb[] = "neural_equals_phrase_db";

class EqualsPhraseDb final : public rime::StableDb {
public:
    EqualsPhraseDb(const rime::path &file, const rime::string &name) : StableDb(file, name) {
        format_.parser = [parse = std::move(format_.parser)](
            const rime::Tsv &row, rime::string *key, rime::string *value) {
            if (row.size() == 1) {
                const auto columns = chinese_ime_lm::equalsPhraseColumns(row.front());
                if (!columns.empty()) return parse(columns, key, value);
            }
            return parse(row, key, value);
        };
    }
};

class EqualsPhraseDbComponent final : public rime::Db::Component, private rime::DbComponentBase {
public:
    rime::Db *Create(const rime::string &name) override {
        return new EqualsPhraseDb(DbFilePath(name, ".txt"), name);
    }
};

class CustomPhraseUserDictionaryComponent final : public rime::UserDictionaryComponent {
public:
    rime::UserDictionary *Create(const rime::Ticket &ticket) override {
        if (ticket.schema) {
            auto *config = ticket.schema->config();
            const auto &section = ticket.name_space;
            bool allowEquals = section == "custom_phrase";
            config->GetBool(section + "/enable_equals_separator", &allowEquals);
            if (allowEquals) {
                rime::string dbClass, file;
                bool enabled = true;
                config->GetBool(section + "/enable_user_dict", &enabled);
                if (enabled && config->GetString(section + "/db_class", &dbClass) &&
                    dbClass == "stabledb" && config->GetString(section + "/user_dict", &file)) {
                    // The original component caches by filename alone. Use a
                    // separate cache so an opted-out schema still reads TSV only.
                    return equalsDictionaries_.Create(file, kEqualsPhraseDb);
                }
            }
        }
        return UserDictionaryComponent::Create(ticket);
    }

private:
    rime::UserDictionaryComponent equalsDictionaries_;
};
} // namespace

void registerNeuralCustomPhrases() {
    auto &registry = rime::Registry::instance();
    registry.Register(kEqualsPhraseDb, new EqualsPhraseDbComponent);
    registry.Register("user_dictionary", new CustomPhraseUserDictionaryComponent);
}
