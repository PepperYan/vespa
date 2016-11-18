// Copyright 2016 Yahoo Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
#include <vespa/fastos/fastos.h>
#include <vespa/log/log.h>
LOG_SETUP("multivaluemapping2_test");
#include <vespa/vespalib/testkit/test_kit.h>
#include <vespa/searchlib/attribute/multi_value_mapping2.h>
#include <vespa/searchlib/attribute/multi_value_mapping2.hpp>
#include <vespa/searchlib/attribute/not_implemented_attribute.h>
#include <vespa/vespalib/util/generationhandler.h>
#include <vespa/vespalib/test/insertion_operators.h>

template <typename EntryT>
void
assertArray(const std::vector<EntryT> &exp, vespalib::ConstArrayRef<EntryT> values)
{
    EXPECT_EQUAL(exp, std::vector<EntryT>(values.cbegin(), values.cend()));
}

template <class MvMapping>
class MyAttribute : public search::NotImplementedAttribute
{
    using MultiValueType = typename MvMapping::MultiValueType;
    using ConstArrayRef = vespalib::ConstArrayRef<MultiValueType>;
    MvMapping &_mvMapping;
    virtual void onCommit() { }
    virtual void onUpdateStat() { }
    virtual void onShrinkLidSpace() {
        uint32_t committedDocIdLimit = getCommittedDocIdLimit();
        _mvMapping.shrink(committedDocIdLimit);
        setNumDocs(committedDocIdLimit);
    }

public:
    MyAttribute(MvMapping &mvMapping)
        : NotImplementedAttribute("test", AttributeVector::Config()),
          _mvMapping(mvMapping)
    {
    }
    virtual bool addDoc(DocId &doc) {
        _mvMapping.addDoc(doc);
        incNumDocs();
        updateUncommittedDocIdLimit(doc);
        return false;
    }
    virtual uint32_t clearDoc(uint32_t docId) {
        assert(docId < _mvMapping.size());
        _mvMapping.set(docId, ConstArrayRef());
        return 1u;
    }
};

template <typename EntryT>
class Fixture
{
    using MvMapping = search::attribute::MultiValueMapping2<EntryT>;
    MvMapping _mvMapping;
    MyAttribute<MvMapping> _attr;
    using generation_t = vespalib::GenerationHandler::generation_t;

public:
    using ConstArrayRef = vespalib::ConstArrayRef<EntryT>;
    Fixture(uint32_t maxSmallArraySize)
        : _mvMapping(maxSmallArraySize),
          _attr(_mvMapping)
    {
    }
    ~Fixture() { }

    void set(uint32_t docId, const std::vector<EntryT> &values) { _mvMapping.set(docId, values); }
    ConstArrayRef get(uint32_t docId) { return _mvMapping.get(docId); }
    void assertGet(uint32_t docId, const std::vector<EntryT> &exp)
    {
        ConstArrayRef act = get(docId);
        EXPECT_EQUAL(exp, std::vector<EntryT>(act.cbegin(), act.cend()));
    }
    void transferHoldLists(generation_t generation) { _mvMapping.transferHoldLists(generation); }
    void trimHoldLists(generation_t firstUsed) { _mvMapping.trimHoldLists(firstUsed); }
    void addDocs(uint32_t numDocs) {
        for (uint32_t i = 0; i < numDocs; ++i) {
            uint32_t doc = 0;
            _attr.addDoc(doc);
        }
        _attr.commit();
        _attr.incGeneration();
    }
    uint32_t size() const { return _mvMapping.size(); }
    void shrink(uint32_t docIdLimit) {
        _attr.setCommittedDocIdLimit(docIdLimit);
        _attr.commit();
        _attr.incGeneration();
        _attr.shrinkLidSpace();
    }
    void clearDocs(uint32_t lidLow, uint32_t lidLimit) {
        _mvMapping.clearDocs(lidLow, lidLimit, _attr);
    }
};

TEST_F("Test that set and get works", Fixture<int>(3))
{
    f.set(1, {});
    f.set(2, {4, 7});
    f.set(3, {5});
    f.set(4, {10, 14, 17, 16});
    f.set(5, {3});
    TEST_DO(f.assertGet(1, {}));
    TEST_DO(f.assertGet(2, {4, 7}));
    TEST_DO(f.assertGet(3, {5}));
    TEST_DO(f.assertGet(4, {10, 14, 17, 16}));
    TEST_DO(f.assertGet(5, {3}));
}

TEST_F("Test that old value is not overwritten while held", Fixture<int>(3))
{
    f.set(3, {5});
    typename F1::ConstArrayRef old3 = f.get(3);
    TEST_DO(assertArray({5}, old3));
    f.set(3, {7});
    f.transferHoldLists(10);
    TEST_DO(assertArray({5}, old3));
    TEST_DO(f.assertGet(3, {7}));
    f.trimHoldLists(10);
    TEST_DO(assertArray({5}, old3));
    f.trimHoldLists(11);
    TEST_DO(assertArray({0}, old3));
}

TEST_F("Test that addDoc works", Fixture<int>(3))
{
    EXPECT_EQUAL(0, f.size());
    f.addDocs(10);
    EXPECT_EQUAL(10u, f.size());
}

TEST_F("Test that shrink works", Fixture<int>(3))
{
    f.addDocs(10);
    EXPECT_EQUAL(10u, f.size());
    f.shrink(5);
    EXPECT_EQUAL(5u, f.size());
}

TEST_F("Test that clearDocs works", Fixture<int>(3))
{
    f.addDocs(10);
    f.set(1, {});
    f.set(2, {4, 7});
    f.set(3, {5});
    f.set(4, {10, 14, 17, 16});
    f.set(5, {3});
    f.clearDocs(3, 5);
    TEST_DO(f.assertGet(1, {}));
    TEST_DO(f.assertGet(2, {4, 7}));
    TEST_DO(f.assertGet(3, {}));
    TEST_DO(f.assertGet(4, {}));
    TEST_DO(f.assertGet(5, {3}));
}

TEST_MAIN() { TEST_RUN_ALL(); }